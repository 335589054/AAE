/**
 * 自检脚本：用 Sample/ 的真实数据验证底层引擎。
 *
 * 覆盖：
 *  1. 二进制 AndroidManifest（AXML）解析与包名替换；
 *  2. 用 Sample 组装一个测试 APK（含未压缩的 lib/**.so，用于验证页对齐）；
 *  3. ApkProject 的读取、增删改查、资源导入；
 *  4. exportUnsigned 重新打包后的完整性与对齐；
 *  5. 用导出的 APK 重新打开工程，确认可再次解析。
 *
 * 运行：npm run selftest
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { ZipReader, ZipWriter } from '../electron/core/zip';
import { readManifest, parseAxmlElements, setPackageName, collectPackageDerivedIndices } from '../electron/core/axml';
import { ApkProject, createSongTemplate, createSongWithResources, PACK_DIR } from '../electron/core/project';
import { inspectSongZip } from '../electron/core/songzip';
import {
  explainInstallFailure,
  parsePushPercent,
  parsePushSummary,
  parseRemoteSize,
} from '../electron/core/install';
import { resolveToolset } from '../electron/core/prereq';
import { ensureAutoKeystore, signApk, verifyApk } from '../electron/core/signer';

const ROOT = process.cwd();
const WORK = fs.mkdtempSync(path.join(os.tmpdir(), 'aam-selftest-'));
let failures = 0;

function check(label: string, condition: boolean, extra?: unknown): void {
  if (condition) {
    console.log(`  PASS  ${label}`);
  } else {
    failures++;
    console.log(`  FAIL  ${label}${extra === undefined ? '' : ` → ${String(extra).slice(0, 400)}`}`);
  }
}

function section(title: string): void {
  console.log(`\n=== ${title} ===`);
}

function walk(dir: string, base = dir): Array<{ rel: string; abs: string }> {
  const out: Array<{ rel: string; abs: string }> = [];
  for (const item of fs.readdirSync(dir, { withFileTypes: true })) {
    const abs = path.join(dir, item.name);
    if (item.isDirectory()) out.push(...walk(abs, base));
    else out.push({ rel: path.relative(base, abs).split(path.sep).join('/'), abs });
  }
  return out;
}

const STORE_EXT = new Set(['.png', '.jpg', '.jpeg', '.ogg', '.zip', '.so', '.jar']);

/** 一个最小的合法 PNG，用于测试导入封面 */
const FAKE_PNG = Buffer.from(
  '89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c6300010000050001' +
    '0d0a2db40000000049454e44ae426082',
  'hex',
);

/* ---------------------- 0. 前置资源检测 ---------------------- */

section('0. 前置资源检测');
const tools = resolveToolset(true);
console.log(`  java      : ${tools.java ?? '(未找到)'}`);
console.log(`  adb       : ${tools.adb ?? '(未找到)'}`);
console.log(`  apksigner : ${tools.apksignerJar ?? '(未找到)'}`);
console.log(`  apktool   : ${tools.apktoolJar ?? '(未找到，可选)'}`);
check('检测到 java 运行环境', Boolean(tools.java));

/* ---------------------- 1. AXML 解析与包名替换 ---------------------- */

section('1. AndroidManifest（AXML）');

const manifestBuf = fs.readFileSync(path.join(ROOT, 'Sample', 'AndroidManifest.xml'));
const info = readManifest(manifestBuf);
console.log(`  包名: ${info.packageName}`);
console.log(`  版本: ${info.versionName} (${info.versionCode})`);
console.log(`  SDK : min=${info.minSdk} target=${info.targetSdk}`);
console.log(`  主 Activity: ${info.mainActivity}`);
console.log(`  组件类名数: ${info.componentNames.length}`);
console.log(`  相对类名（以 . 开头，改包名会受影响）: ${info.componentNames.filter((n) => n.startsWith('.')).length}`);

check('能解析出版本名', typeof info.versionName === 'string');
check('能解析出 versionCode', typeof info.versionCode === 'number');

// 列出清单里与包名相关的标识符，用于验证改名时的一致性改写
{
  const elements = parseAxmlElements(manifestBuf);
  const pick = (element: string, attribute: string): string[] =>
    elements
      .filter((el) => el.name === element)
      .map((el) => String(el.attributes.find((a) => a.name === attribute)?.value ?? ''))
      .filter(Boolean);
  const declared = pick('permission', 'name');
  const used = pick('uses-permission', 'name').filter((name) => name.startsWith(info.packageName));
  const authorities = pick('provider', 'authorities');
  const protectedBy = pick('receiver', 'permission').concat(pick('service', 'permission'));
  console.log(`  自定义权限声明: ${declared.join(', ') || '(无)'}`);
  console.log(`  引用自有权限  : ${used.join(', ') || '(无)'}`);
  console.log(`  Provider 授权 : ${authorities.join(' | ') || '(无)'}`);
  console.log(`  受权限保护的组件声明: ${protectedBy.join(', ') || '(无)'}`);
  check('清单中存在以包名为前缀的自定义标识符', declared.length + authorities.length > 0);
}

/* --------------- 改包名时同步改写全机唯一的标识符 --------------- */

const rewriteTarget = 'com.example.arcaea.mo1.mod1';

/** 取出清单里所有自定义权限名、Provider 授权名与组件类名 */
function collectIdentifiers(buf: Buffer): {
  permissions: string[];
  authorities: string[];
  classNames: string[];
} {
  const elements = parseAxmlElements(buf);
  const valuesOf = (element: string, attribute: string): string[] =>
    elements
      .filter((el) => el.name === element)
      .map((el) => String(el.attributes.find((a) => a.name === attribute)?.value ?? ''))
      .filter(Boolean);
  return {
    permissions: valuesOf('permission', 'name').concat(valuesOf('uses-permission', 'name')),
    authorities: valuesOf('provider', 'authorities'),
    classNames: valuesOf('activity', 'name')
      .concat(valuesOf('service', 'name'))
      .concat(valuesOf('receiver', 'name'))
      .concat(valuesOf('provider', 'name'))
      .concat(valuesOf('application', 'name')),
  };
}

{
  const derivedCount = new Set(collectPackageDerivedIndices(manifestBuf, info.packageName)).size;
  const identifiersBefore = collectIdentifiers(manifestBuf);

  const rewritten = setPackageName(manifestBuf, rewriteTarget, { rewriteIdentifiers: true });
  const rewrittenInfo = readManifest(rewritten);
  const identifiersAfter = collectIdentifiers(rewritten);

  check('识别出需要跟随改名的字符串', derivedCount >= 2, derivedCount);
  check('包名已替换', rewrittenInfo.packageName === rewriteTarget, rewrittenInfo.packageName);
  check(
    '自定义权限名跟随新包名',
    identifiersAfter.permissions.every((name) => !name.startsWith('moe.high.ard.')) &&
      identifiersAfter.permissions.includes(`${rewriteTarget}.permission.C2D_MESSAGE`),
    identifiersAfter.permissions.join(', '),
  );
  check(
    'Provider 授权名跟随新包名',
    identifiersAfter.authorities.every((name) => !name.startsWith('moe.high.ard.')) &&
      identifiersAfter.authorities.includes(`${rewriteTarget}.provider`),
    identifiersAfter.authorities.join(' | '),
  );
  check(
    '组件类名未被改动（避免改坏类引用）',
    JSON.stringify(identifiersAfter.classNames) === JSON.stringify(identifiersBefore.classNames),
  );
  check(
    '版本信息保持一致',
    rewrittenInfo.versionName === info.versionName && rewrittenInfo.versionCode === info.versionCode,
  );

  const packageOnly = setPackageName(manifestBuf, rewriteTarget, { rewriteIdentifiers: false });
  const identifiersPackageOnly = collectIdentifiers(packageOnly);
  check(
    '关闭改写时保留原有权限名与授权名',
    identifiersPackageOnly.permissions.includes('moe.high.ard.permission.C2D_MESSAGE') &&
      identifiersPackageOnly.authorities.includes('moe.high.ard.provider'),
    identifiersPackageOnly.authorities.join(' | '),
  );
}

for (const candidate of ['com.example.arcaea.short', 'com.example.arcaea.a.much.longer.package.name']) {
  const patched = setPackageName(manifestBuf, candidate);
  const reparsed = readManifest(patched);
  check(
    `替换包名后可重新解析且值正确（${candidate}）`,
    reparsed.packageName === candidate,
    reparsed.packageName,
  );
  check(`替换包名后版本名不变（${candidate}）`, reparsed.versionName === info.versionName);
  check(
    `替换包名后组件类名数量不变（${candidate}）`,
    reparsed.componentNames.length === info.componentNames.length,
  );
  check(`替换包名后主 Activity 不变（${candidate}）`, reparsed.mainActivity === info.mainActivity);
}

/* ---------------------- 2. 组装测试用 APK ---------------------- */

section('2. 用 Sample 组装测试 APK');

const baseApk = path.join(WORK, 'base.apk');
{
  const files = walk(path.join(ROOT, 'Sample'));
  const writer = new ZipWriter(baseApk);
  for (const file of files) {
    const ext = path.extname(file.rel).toLowerCase();
    writer.addBuffer(file.rel, fs.readFileSync(file.abs), { compress: !STORE_EXT.has(ext) });
  }
  // 放一个未压缩的“原生库”，用来验证导出时的 4096 字节页对齐
  writer.addBuffer('lib/arm64-v8a/libcocos2dcpp.so', Buffer.alloc(64 * 1024, 7), {
    compress: false,
  });
  writer.finish();
  console.log(`  已生成 ${baseApk}（${(fs.statSync(baseApk).size / 1024 / 1024).toFixed(1)} MB，${files.length} 个条目）`);
}

/* ---------------------- 3. 打开工程并读取 ---------------------- */

section('3. ApkProject 读取与一致性检查');

const project = ApkProject.open(baseApk);
const snapshot = project.snapshot();
console.log(`  包名: ${snapshot.apk.packageName}`);
console.log(`  歌曲: ${snapshot.songs.length} 首，曲包: ${snapshot.packs.length} 个，目录: ${snapshot.apk.songDirs.length} 个`);
console.log(`  一致性告警: 问题 ${snapshot.warnings.length} 条，提示 ${snapshot.notes.length} 条`);
for (const warning of snapshot.warnings.slice(0, 6)) console.log(`    [!] ${warning}`);
for (const note of snapshot.notes.slice(0, 4)) console.log(`    [·] ${note}`);

check('解析出 27 首歌曲', snapshot.songs.length === 27, snapshot.songs.length);
check('解析出 7 个曲包', snapshot.packs.length === 7, snapshot.packs.length);
check('未做修改时 pending 为 0', snapshot.pending.total === 0, snapshot.pending.total);
check('未导出时 dirtySinceExport 为 false', snapshot.dirtySinceExport === false);

const axdcut12 = snapshot.songs.find((s) => s.song.id === 'axdcut12');
check('axdcut12 的难度文件状态被正确识别', Boolean(axdcut12?.charts.some((c) => c.hasChart)));

/* ---------------------- 4. 增删改查 ---------------------- */

section('4. 增删改查');

project.updateSong('bloomingplanet', { title_localized: { en: 'SELFTEST TITLE' } });
project.addPack({
  id: 'selftestpack',
  section: 'sidestory',
  custom_banner: true,
  cutout_pack_image: true,
  plus_character: -1,
  name_localized: { en: 'Selftest Pack' },
  description_localized: { en: '', ja: '' },
});
project.addSong(createSongTemplate('selftestsong', 'selftestpack'));
project.removeSong('iscut11');
project.updateSong('iscut12', { id: 'iscut12renamed' });

// 模拟导入封面：写一个最小 PNG 后走 importFile 路径
const fakePng = path.join(WORK, 'fake.png');
fs.writeFileSync(fakePng, FAKE_PNG);
project.stageWrite('assets/songs/selftestsong/base.jpg', { kind: 'file', srcPath: fakePng });
project.stageWrite('assets/songs/selftestsong/base_256.jpg', { kind: 'file', srcPath: fakePng });
project.stageWrite(`${PACK_DIR}select_selftestpack.png`, { kind: 'file', srcPath: fakePng });
project.setPackageNameOverride('com.example.arcaea.mod');

const afterEdits = project.snapshot();
console.log(`  待导出改动: ${afterEdits.pending.total} 项`);
check('改动被计入 pending', afterEdits.pending.total > 0, afterEdits.pending.total);
check('包名变更被记录', afterEdits.pending.packageChanged === true);
check('dirtySinceExport 变为 true', afterEdits.dirtySinceExport === true);
check(
  '重命名后旧目录从 songDirs 中移除',
  !afterEdits.apk.songDirs.includes('iscut12') && afterEdits.apk.songDirs.includes('iscut12renamed'),
);
check(
  '重命名后新目录能看到原有谱面',
  afterEdits.songs.find((s) => s.song.id === 'iscut12renamed')?.files.includes('2.aff') === true,
);

/* ---------------------- 4.5 资源 zip 导入 ---------------------- */

section('4.6 规则校验：普通曲包不能为空（空包会导致游戏打开歌单时闪退）');

{
  // 原始 mod 里的空曲包都带 is_extend_pack: true，属于安全状态
  const baseline = project.snapshot();
  check(
    'extend 包为空只提示、不报问题',
    !baseline.warnings.some((w) => w.includes('「lowest」')) &&
      baseline.notes.some((n) => n.includes('「lowest」')),
    baseline.notes.filter((n) => n.includes('lowest')).join(' / '),
  );

  // 新建一个普通曲包（无 is_extend_pack）且不放歌曲 → 必须报问题
  project.addPack({ id: 'emptypack', section: 'arcaea', name_localized: { en: 'Empty' } } as never);
  const withEmpty = project.snapshot();
  check(
    '普通空曲包被列为问题（而非提示）',
    withEmpty.warnings.some((w) => w.includes('emptypack') && w.includes('is_extend_pack')),
    withEmpty.warnings.join(' / ').slice(0, 200),
  );

  // 加上 is_extend_pack 后应降级为提示
  project.updatePack('emptypack', { is_extend_pack: true });
  const withExtend = project.snapshot();
  check(
    '标记为 extend 后空包不再报警',
    !withExtend.warnings.some((w) => w.includes('emptypack') && w.includes('is_extend_pack')),
    withExtend.warnings.filter((w) => w.includes('emptypack')).join(' / '),
  );

  // 清理，避免影响后面的导出校验
  project.removePack('emptypack');
  check('清理后恢复干净状态', !project.snapshot().warnings.some((w) => w.includes('emptypack')));
}

/* ---------------------- 4.7 资源 zip 导入 ---------------------- */

section('4.7 资源 zip 导入（新增歌曲）');

// 形态 A：根目录扁平文件 + songlist.txt 元数据片段 + __MACOSX 干扰项
const zipFlat = path.join(WORK, 'song-flat.zip');
{
  const writer = new ZipWriter(zipFlat);
  writer.addBuffer('base.jpg', FAKE_PNG, { compress: false });
  writer.addBuffer('3.aff', Buffer.from('AudioOffset:0\n-\ntiming(0,180.00,4.00);\n'));
  writer.addBuffer('base.ogg', Buffer.alloc(4096, 3), { compress: false });
  writer.addBuffer(
    'songlist.txt',
    Buffer.from(
      JSON.stringify(
        {
          title_localized: { en: 'Zip Song A' },
          artist: 'Zip Artist',
          bpm: '180',
          bpm_base: 180,
          difficulties: [
            { ratingClass: 2, rating: 9, chartDesigner: 'ZipCharter' },
            { ratingClass: 3, rating: 10, ratingPlus: true },
          ],
        },
        null,
        2,
      ),
    ),
  );
  writer.addBuffer('__MACOSX/._base.jpg', Buffer.from('junk'));
  writer.finish();
}

const inspectA = inspectSongZip(zipFlat);
console.log(`  形态A 资源: ${inspectA.resources.join(', ') || '(无)'}`);
check(
  '扁平包识别出 3 个资源（songlist.txt 归为元数据、__MACOSX 被忽略）',
  inspectA.resources.length === 3,
  inspectA.resources.join(','),
);
check(
  '元数据片段不计入资源',
  !inspectA.resources.includes('songlist.txt') && inspectA.skipped.includes('songlist.txt'),
  inspectA.skipped.join(','),
);
check(
  '忽略 __MACOSX 等系统文件',
  !inspectA.resources.some((name) => name.includes('__MACOSX')),
);
check('未误剥离根目录', inspectA.root === null, inspectA.root);
check('封面 / 谱面 / 音频都被识别', inspectA.hasJacket && inspectA.hasChart && inspectA.hasAudio);
check(
  '解析出 songlist.txt 元数据',
  inspectA.metadata?.title_localized?.en === 'Zip Song A' && inspectA.metadata?.artist === 'Zip Artist',
  JSON.stringify(inspectA.metadata),
);
check('元数据中的难度被解析', inspectA.metadata?.difficulties?.length === 2);

const createdFlat = createSongWithResources(project, {
  id: 'zipsongflat',
  setId: 'selftestpack',
  zipPath: zipFlat,
});
check(
  'zip 元数据写入新歌曲（用户未填曲名时采用包内曲名）',
  (createdFlat.title_localized as { en?: string } | undefined)?.en === 'Zip Song A',
  JSON.stringify(createdFlat.title_localized),
);
check('zip 元数据不覆盖用户填写的 id / 曲包', createdFlat.id === 'zipsongflat' && createdFlat.set === 'selftestpack');
check(
  '资源已登记到新歌曲目录',
  project.listSongFiles('zipsongflat').includes('3.aff') &&
    project.listSongFiles('zipsongflat').includes('base.jpg'),
);

// 形态 B：带 assets/songs/<id>/ 前缀（模拟直接压缩解包目录的一部分）
const zipPrefixed = path.join(WORK, 'song-prefixed.zip');
{
  const writer = new ZipWriter(zipPrefixed);
  for (const name of ['1080_base.jpg', '1080_base_256.jpg', '2.aff', '3.aff', '2.ogg', '3.ogg']) {
    const source = path.join(ROOT, 'Sample', 'assets', 'songs', 'axdcut12', name);
    const ext = path.extname(name).toLowerCase();
    writer.addBuffer(`assets/songs/axdcut12/${name}`, fs.readFileSync(source), {
      compress: !STORE_EXT.has(ext),
    });
  }
  writer.finish();
}

const inspectB = inspectSongZip(zipPrefixed);
check(
  '剥离 assets/songs/<id>/ 前缀',
  inspectB.resources.length === 6 && inspectB.resources.includes('1080_base.jpg'),
  inspectB.resources.join(','),
);
check('前缀包被识别为封面/谱面/音频齐全', inspectB.hasJacket && inspectB.hasChart && inspectB.hasAudio);

createSongWithResources(project, {
  id: 'zipsongprefixed',
  setId: 'selftestpack',
  zipPath: zipPrefixed,
});
check(
  '带前缀的资源被写入新歌曲目录',
  project.listSongFiles('zipsongprefixed').includes('2.aff') &&
    project.listSongFiles('zipsongprefixed').includes('1080_base.jpg'),
);

let exportError: unknown = null;
const outApk = path.join(WORK, 'out.apk');
/* ---------------------- 5. 导出 ---------------------- */

section('5. 重新打包（直通 + 对齐 + 清单补丁）');

try {
  project.exportUnsigned(outApk, (message, percent) => {
    if (percent !== undefined && Math.round(percent) % 25 === 0) console.log(`  … ${message}`);
  });
} catch (err) {
  exportError = err;
}
check('导出未抛异常', exportError === null, exportError);
console.log(`  产物: ${outApk}（${(fs.statSync(outApk).size / 1024 / 1024).toFixed(1)} MB）`);

if (exportError === null) {
  const reader = ZipReader.open(outApk);

  const outManifest = reader.readFile('AndroidManifest.xml');
  check('产物包含 AndroidManifest.xml', outManifest !== null);
  if (outManifest) {
    const outInfo = readManifest(outManifest);
    check('包名已按要求替换', outInfo.packageName === 'com.example.arcaea.mod', outInfo.packageName);
    check('版本信息保持', outInfo.versionName === info.versionName);
  }

  const songlistRaw = reader.readText('assets/songs/songlist');
  check('产物包含 songlist', songlistRaw !== null);
  if (songlistRaw) {
    const parsed = JSON.parse(songlistRaw) as { songs: Array<{ id: string; title_localized?: { en?: string } }> };
    const ids = parsed.songs.map((s) => s.id);
    check('新增歌曲出现在 songlist', ids.includes('selftestsong'));
    check('重命名后的歌曲出现在 songlist', ids.includes('iscut12renamed'));
    check('删除的歌曲已从 songlist 移除', !ids.includes('iscut11'));
    check(
      '曲名修改已写入',
      parsed.songs.find((s) => s.id === 'bloomingplanet')?.title_localized?.en === 'SELFTEST TITLE',
    );
    check('歌曲总数正确（27 - 1 + 3）', parsed.songs.length === 29, parsed.songs.length);
    check(
      'zip 导入的歌曲也写入了 songlist',
      ids.includes('zipsongflat') && ids.includes('zipsongprefixed'),
      ids.join(','),
    );
  }

  const packlistRaw = reader.readText('assets/songs/packlist');
  check('新增曲包写入 packlist', Boolean(packlistRaw?.includes('selftestpack')));

  check(
    '重命名后新目录资源存在',
    reader.has('assets/songs/iscut12renamed/2.aff') && reader.has('assets/songs/iscut12renamed/2.ogg'),
  );
  check('旧目录资源已删除', !reader.has('assets/songs/iscut11/2.aff'));
  check(
    '导入的封面已写入',
    reader.has('assets/songs/selftestsong/base.jpg') && reader.has('assets/songs/selftestsong/base_256.jpg'),
  );
  check(
    'zip 导入的资源已写入 APK',
    reader.has('assets/songs/zipsongflat/3.aff') &&
      reader.has('assets/songs/zipsongflat/base.ogg') &&
      reader.has('assets/songs/zipsongprefixed/2.aff') &&
      reader.has('assets/songs/zipsongprefixed/1080_base.jpg'),
  );
  check('导入的曲包横幅已写入', reader.has(`${PACK_DIR}select_selftestpack.png`));

  const metaInf = reader.list('META-INF/');
  check(
    '原有签名文件已移除',
    metaInf.length === 0 || !metaInf.some((n) => /\.(RSA|DSA|EC|SF)$/i.test(n) || n === 'META-INF/MANIFEST.MF'),
    metaInf.join(','),
  );

  // 对齐检查
  let misaligned4 = 0;
  let misalignedSo = 0;
  for (const entry of reader.entries.values()) {
    if (entry.dataOffset % 4 !== 0) misaligned4++;
    if (entry.name.startsWith('lib/') && entry.name.endsWith('.so') && entry.method === 0) {
      if (entry.dataOffset % 4096 !== 0) misalignedSo++;
    }
  }
  check('所有条目 4 字节对齐', misaligned4 === 0, misaligned4);
  check('未压缩的 .so 页对齐（4096）', misalignedSo === 0, misalignedSo);

  // 严格校验：本地文件头中的 CRC / 大小必须与中央目录一致。
  // Android 的 apksig 会据此判断 APK 是否合法，不一致会直接导致签名与安装失败。
  {
    const fd = fs.openSync(outApk, 'r');
    const head = Buffer.alloc(30);
    let mismatched = 0;
    let firstBad = '';
    for (const entry of reader.entries.values()) {
      fs.readSync(fd, head, 0, 30, entry.localHeaderOffset);
      const crc = head.readUInt32LE(14);
      const compressedSize = head.readUInt32LE(18);
      const uncompressedSize = head.readUInt32LE(22);
      const nameLen = head.readUInt16LE(26);
      const consistent =
        crc === entry.crc32 &&
        compressedSize === entry.compressedSize &&
        uncompressedSize === entry.uncompressedSize &&
        nameLen === Buffer.byteLength(entry.name);
      if (!consistent) {
        mismatched++;
        if (!firstBad) firstBad = entry.name;
      }
    }
    fs.closeSync(fd);
    check('本地文件头与中央目录一致（CRC / 大小 / 名称长度）', mismatched === 0, `${mismatched} 处不一致，首个：${firstBad}`);
  }

  // 完整性：逐个解压
  let readFailures = 0;
  let totalBytes = 0;
  for (const name of reader.listAll()) {
    const data = reader.readFile(name);
    if (!data) readFailures++;
    else totalBytes += data.length;
  }
  check('所有条目均可正常解压', readFailures === 0, readFailures);
  console.log(`  解压总大小: ${(totalBytes / 1024 / 1024).toFixed(1)} MB`);

  // 未改动条目是否与原 APK 字节一致（直通拷贝的正确性）
  {
    const baseReader = ZipReader.open(baseApk);
    const same: string[] = [];
    for (const name of ['assets/songs/axdcut12/2.aff', 'assets/songs/axdcut12/1080_base.jpg', 'assets/Fonts/Exo-Regular.ttf']) {
      const a = baseReader.readFile(name);
      const b = reader.readFile(name);
      if (a && b && a.equals(b)) same.push(name);
    }
    check('未改动条目内容保持一致', same.length === 3, same.join(','));
    baseReader.close();
  }

  reader.close();

  /* ---------------------- 6. 重新打开导出结果 ---------------------- */

  section('6. 用导出结果重新打开工程');
  const reopened = ApkProject.open(outApk);
  const reopenedSnapshot = reopened.snapshot();
  console.log(`  包名: ${reopenedSnapshot.apk.packageName}`);
  console.log(`  歌曲: ${reopenedSnapshot.songs.length} 首`);
  check('导出的 APK 可被正常解析', reopenedSnapshot.songs.length === 29, reopenedSnapshot.songs.length);
  check('导出后无残留待导出改动', reopenedSnapshot.pending.total === 0, reopenedSnapshot.pending.total);
  check(
    '重命名后的歌曲目录存在',
    reopenedSnapshot.apk.songDirs.includes('iscut12renamed'),
  );
  reopened.close();
}

project.close();

/* ---------------------- 6.5 安装进度解析 ---------------------- */

section('6.5 安装进度与失败提示解析（纯函数，无需真机）');

check(
  '从 adb push 输出解析百分比（取最后一次更新）',
  parsePushPercent('[  0%] /data/local/tmp/a.apk\r[ 42%] /data/local/tmp/a.apk\r[100%] /data/local/tmp/a.apk') ===
    100,
);
check(
  'adb push 未输出进度时返回 null（触发兜底探测）',
  parsePushPercent('/data/local/tmp/a.apk: 1 file pushed, 0 skipped.') === null,
);
check(
  '解析 push 摘要中的平均速率',
  parsePushSummary('a.apk: 1 file pushed, 0 skipped. 25.4 MB/s (637123456 bytes in 25.1s)') === '25.4MB/s',
  parsePushSummary('a.apk: 1 file pushed, 0 skipped. 25.4 MB/s (637123456 bytes in 25.1s)'),
);
check(
  '从 ls -l 输出解析设备端文件大小（兜底进度）',
  parseRemoteSize(
    '-rw-rw-rw- 1 shell shell 12345678 2026-09-13 12:00 aam_abc_arcaea_mod.apk',
    'aam_abc',
  ) === 12345678,
);
check(
  'ls -l 中不含本次任务的文件时不误判',
  parseRemoteSize('-rw-rw-rw- 1 shell shell 999 2026-09-13 12:00 other.apk', 'aam_abc') === null,
);
check(
  '多设备失败给出选择设备的提示',
  explainInstallFailure('adb: more than one device/emulator').includes('选择目标设备'),
);
check(
  '签名冲突失败给出卸载/改包名提示',
  explainInstallFailure('Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]').includes('签名不一致'),
);
check(
  '存储不足失败给出存储提示',
  explainInstallFailure('Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]').includes('存储空间不足'),
);

{
  const conflicting =
    'adb.exe: Failure [INSTALL_FAILED_DUPLICATE_PERMISSION: Package moe.high.ard.mo1.mod1 ' +
    'attempting to redeclare permission moe.high.ard.permission.C2D_MESSAGE already owned by moe.high.ard]';
  const guide = explainInstallFailure(conflicting);
  check('重复权限失败给出专用引导', guide.includes('已被其它应用占用'), guide.slice(0, 80));
  check('引导中包含冲突权限名', guide.includes('moe.high.ard.permission.C2D_MESSAGE'));
  check(
    '引导中包含「改写标识符」与「卸载原版」两条可执行方案',
    guide.includes('同时改写自定义权限名与 Provider 授权名') && guide.includes('卸载'),
  );
  const providerConflict = explainInstallFailure(
    'Failure [INSTALL_FAILED_CONFLICTING_PROVIDER: authority moe.high.ard.provider already used]',
  );
  check('授权名冲突给出专用引导', providerConflict.includes('授权名'));
}

/* ---------------------- 7. 签名（apksigner） ---------------------- */

void (async () => {
  section('7. 签名与校验（apksigner）');
  if (!tools.java || !tools.apksignerJar) {
    console.log('  跳过：本机缺少 java 或 apksigner，无法验证签名流程');
  } else {
    // 释放临时空间后签名
    fs.rmSync(baseApk, { force: true });
    const signedApk = path.join(WORK, 'signed.apk');
    let signError: unknown = null;
    try {
      const keystore = await ensureAutoKeystore(tools.java, (message) => console.log(`  ${message}`));
      await signApk({
        javaExe: tools.java,
        apksignerJar: tools.apksignerJar,
        keystore,
        inputApk: outApk,
        outputApk: signedApk,
        log: (message) => console.log(`  ${message}`),
      });
    } catch (err) {
      signError = err;
    }
    check('apksigner 签名成功', signError === null, signError);

    if (signError === null) {
      const verified = await verifyApk(tools.java, tools.apksignerJar, signedApk);
      check('apksigner verify 通过', verified.ok, verified.output.slice(0, 600));
      console.log(
        `  签名产物: ${(fs.statSync(signedApk).size / 1024 / 1024).toFixed(1)} MB`,
      );

      const signedReader = ZipReader.open(signedApk);
      const sigFiles = signedReader.list('META-INF/');
      check(
        '已生成 v1 签名文件',
        sigFiles.some((n) => /\.(RSA|DSA|EC)$/i.test(n)) || sigFiles.includes('META-INF/MANIFEST.MF'),
        sigFiles.join(','),
      );
      signedReader.close();

      const afterSign = ApkProject.open(signedApk);
      const afterSignSnapshot = afterSign.snapshot();
      check('签名后的 APK 仍可被解析', afterSignSnapshot.songs.length === 29, afterSignSnapshot.songs.length);
      check('签名后包名保持为新包名', afterSignSnapshot.apk.packageName === 'com.example.arcaea.mod');
      afterSign.close();
    }
  }

  section('结果');
  if (failures === 0) {
    console.log('  全部通过 ✔');
  } else {
    console.log(`  ${failures} 项失败 ✘`);
  }
  fs.rmSync(WORK, { recursive: true, force: true });
  process.exit(failures === 0 ? 0 : 1);
})();
