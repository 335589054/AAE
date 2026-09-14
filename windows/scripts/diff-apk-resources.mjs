/**
 * 诊断一个已构建的 Arcaea APK：把「APK 内的 songlist」与「APK 内的实际文件」做交叉比对，
 * 并可与原始（未修改的）songlist 逐条 diff，用来定位导致游戏闪退的资源问题。
 *
 * 用法（先在设备上取数据，再在本机运行）：
 *   adb shell "unzip -l <已安装APK路径> 'assets/songs/*' 'assets/img/bg/1080/*' > /sdcard/Download/aam/listing.txt"
 *   adb shell "unzip -o -p <已安装APK路径> assets/songs/songlist > /sdcard/Download/aam/songlist.json"
 *   adb shell "unzip -o -p <已安装APK路径> assets/songs/packlist > /sdcard/Download/aam/packlist.json"
 *   adb pull /sdcard/Download/aam/ ./pulled/
 *   node scripts/diff-apk-resources.mjs ./pulled [原始songlist路径]
 *
 * 也可以直接对导出的 APK 跑：把 listing.txt 换成 `unzip -l 你的.apk ...` 的输出即可。
 */
import fs from 'node:fs';
import path from 'node:path';

const dir = process.argv[2];
if (!dir || !fs.existsSync(dir)) {
  console.error('用法: node scripts/diff-apk-resources.mjs <包含 listing.txt/songlist.json 的目录> [原始songlist路径]');
  process.exit(2);
}

const readJson = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));
const listingPath = path.join(dir, 'listing.txt');
const listing = fs.readFileSync(listingPath, 'utf8');

/** 解析 `unzip -l` 输出：大小 / 日期 / 路径 */
const entries = [];
for (const line of listing.split(/\r?\n/)) {
  const match = /^\s*(\d+)\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2})\s+(.+?)\s*$/.exec(line);
  if (match) entries.push({ size: Number(match[1]), date: match[2], name: match[3] });
}
const present = new Set(entries.map((e) => e.name));
console.log(`清单条目数: ${entries.length}`);

console.log('\n=== 最近被写入的条目（2026-09 之后，通常是本次改动） ===');
const recent = entries
  .filter((e) => e.date >= '2026-09-01')
  .sort((a, b) => a.name.localeCompare(b.name));
if (recent.length === 0) console.log('  （无）');
for (const e of recent) console.log(`  ${e.date}  ${String(e.size).padStart(9)}B  ${e.name}`);

const device = readJson(path.join(dir, 'songlist.json'));
const packlistPath = path.join(dir, 'packlist.json');
const packIds = fs.existsSync(packlistPath)
  ? new Set((readJson(packlistPath).packs ?? []).map((p) => p.id))
  : null;
const dSongs = device.songs ?? [];

let oSongs = [];
const originalArg = process.argv[3];
if (originalArg && fs.existsSync(originalArg)) {
  oSongs = readJson(originalArg).songs ?? [];
}
const oById = new Map(oSongs.map((s) => [s.id, s]));
const dById = new Map(dSongs.map((s) => [s.id, s]));

console.log('\n=== songlist 概览 ===');
console.log(`  设备 ${dSongs.length} 首；原始 ${oSongs.length} 首；曲包 ${packIds ? packIds.size : '(未提供)'} 个`);
const ids = dSongs.map((s) => s.id);
const dupes = [...new Set(ids.filter((id, i) => ids.indexOf(id) !== i))];
if (dupes.length) console.log(`  ⚠ 重复 id: ${dupes.join(', ')}`);
if (oSongs.length) {
  const added = dSongs.filter((s) => !oById.has(s.id)).map((s) => s.id);
  const removed = oSongs.filter((s) => !dById.has(s.id)).map((s) => s.id);
  console.log(`  新增: ${added.length ? added.join(', ') : '（无）'}`);
  console.log(`  删除: ${removed.length ? removed.join(', ') : '（无）'}`);
}

console.log('\n=== 逐曲资源一致性（只列问题） ===');
let issues = 0;
for (const song of dSongs) {
  const prefix = `assets/songs/${song.id}/`;
  const own = [...present].filter((n) => n.startsWith(prefix)).map((n) => n.slice(prefix.length));
  const has = (file) => own.includes(file);
  const problems = [];

  if (own.length === 0) {
    problems.push('整个歌曲目录不存在');
  } else {
    const covers = ['base.jpg', 'base_256.jpg', '1080_base.jpg', '1080_base_256.jpg'].filter(has);
    if (covers.length === 0) problems.push('没有任何封面文件');

    const difficulties = Array.isArray(song.difficulties) ? song.difficulties : [];
    if (difficulties.length === 0) problems.push('未定义任何难度');

    for (const difficulty of difficulties) {
      const rc = difficulty.ratingClass;
      const rating = difficulty.rating ?? 0;
      if (!has(`${rc}.aff`) && rating > 0) {
        problems.push(`难度 ${rc}(定数 ${rating}) 缺少谱面 ${rc}.aff`);
      }
      if (difficulty.audioOverride && !has(`${rc}.ogg`)) {
        problems.push(`难度 ${rc} 声明 audioOverride 但缺少 ${rc}.ogg`);
      }
    }

    const playable = difficulties.some((d) => (d.rating ?? 0) > 0);
    const anyOverride = difficulties.some((d) => d.audioOverride);
    if (playable && !has('base.ogg') && !anyOverride) {
      problems.push('缺少 base.ogg 且没有 audioOverride，播放将无声');
    }

    for (const file of own) {
      const match = /^(\d+)\.aff$/.exec(file);
      if (match && !difficulties.some((d) => d.ratingClass === Number(match[1]))) {
        problems.push(`存在 ${file} 但 songlist 未声明该难度`);
      }
    }
  }

  if (song.bg) {
    if (!present.has(`assets/img/bg/1080/${song.bg}.jpg`) && !present.has(`assets/img/bg/1080/${song.bg}.png`)) {
      problems.push(`背景资源缺失: ${song.bg}.jpg`);
    }
  } else {
    problems.push('未设置 bg（游玩背景）');
  }

  if (packIds && song.set && !packIds.has(song.set)) {
    problems.push(`set="${song.set}" 在 packlist 中不存在`);
  }

  if (problems.length) {
    issues += problems.length;
    console.log(`  [${song.id}] ${problems.join(' | ')}`);
  }
}
console.log(issues === 0 ? '  （未发现资源问题）' : `  合计 ${issues} 处问题`);

if (oSongs.length) {
  console.log('\n=== 与原始 songlist 的字段差异 ===');
  let changed = 0;
  for (const song of dSongs) {
    const before = oById.get(song.id);
    if (!before) continue;
    if (JSON.stringify(before) === JSON.stringify(song)) continue;
    changed++;
    const diffs = [];
    for (const key of new Set([...Object.keys(before), ...Object.keys(song)])) {
      const a = JSON.stringify(before[key]);
      const b = JSON.stringify(song[key]);
      if (a !== b) diffs.push(`${key}: ${a} → ${b}`);
    }
    console.log(`  [${song.id}] ${diffs.join(' ; ')}`);
  }
  if (changed === 0) console.log('  （无差异）');
}
