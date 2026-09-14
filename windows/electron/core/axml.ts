/**
 * 二进制 AndroidManifest.xml（AXML）解析与包名替换。
 *
 * 为什么需要它：APK 内的 AndroidManifest.xml 是编译后的二进制格式，
 * 直接改文本会损坏文件。这里实现最小可用的 AXML 读写：
 * - 解析字符串池与元素/属性，读取 package、versionName、versionCode、uses-sdk 等；
 * - 通过「重建字符串池」把 <manifest package="..."> 换成用户指定的包名，
 *   其余条目、其它字符串引用（按索引引用）全部保持不变。
 *
 * 只做包名替换这一件事，不依赖 apktool / aapt2，速度与风险都可控。
 */

const CHUNK_STRING_POOL = 0x0001;
const CHUNK_START_ELEMENT = 0x0102;
const CHUNK_END_ELEMENT = 0x0103;
const CHUNK_XML = 0x0003;

const TYPE_STRING = 0x03;
const TYPE_INT_DEC = 0x10;
const TYPE_INT_HEX = 0x11;
const TYPE_REFERENCE = 0x01;

const NO_INDEX = 0xffffffff;

export interface AxmlAttribute {
  name: string;
  namespace: string | null;
  rawValue: string | null;
  value: string | number | null;
  type: number;
}

export interface AxmlElement {
  name: string;
  attributes: AxmlAttribute[];
  depth: number;
}

export interface ManifestInfo {
  isBinary: boolean;
  packageName: string;
  versionName?: string;
  versionCode?: number;
  minSdk?: number;
  targetSdk?: number;
  compileSdk?: string;
  applicationName?: string;
  mainActivity?: string;
  /** 所有含 android:name 的组件类名（用于判断是否为绝对类名） */
  componentNames: string[];
}

interface StringPool {
  strings: string[];
  utf8: boolean;
  sorted: boolean;
  /** 原字符串池 chunk 的原始字节（用于重建 styles 区块） */
  rawStyles: Buffer;
  styleCount: number;
  stylesStart: number;
  chunkSize: number;
}

function readUleb128(buf: Buffer, offset: number): { value: number; next: number } {
  let result = 0;
  let shift = 0;
  let pos = offset;
  for (;;) {
    const byte = buf[pos++];
    result |= (byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) break;
    shift += 7;
    if (shift > 28) throw new Error('AXML 字符串长度字段损坏');
  }
  return { value: result >>> 0, next: pos };
}

function writeUleb128(value: number): Buffer {
  const bytes: number[] = [];
  let v = value >>> 0;
  do {
    let byte = v & 0x7f;
    v >>>= 7;
    if (v !== 0) byte |= 0x80;
    bytes.push(byte);
  } while (v !== 0);
  return Buffer.from(bytes);
}

function parseStringPool(chunk: Buffer): StringPool {
  const headerSize = chunk.readUInt16LE(2);
  const chunkSize = chunk.readUInt32LE(4);
  const stringCount = chunk.readUInt32LE(8);
  const styleCount = chunk.readUInt32LE(12);
  const flags = chunk.readUInt32LE(16);
  const stringsStart = chunk.readUInt32LE(20);
  const stylesStart = chunk.readUInt32LE(24);

  const utf8 = (flags & 0x100) !== 0;
  const sorted = (flags & 0x1) !== 0;
  const strings: string[] = [];

  for (let i = 0; i < stringCount; i++) {
    const offsetPos = headerSize + i * 4;
    const strOffset = chunk.readUInt32LE(offsetPos);
    const pos = stringsStart + strOffset;
    if (utf8) {
      const chars = readUleb128(chunk, pos);
      const bytes = readUleb128(chunk, chars.next);
      const start = bytes.next;
      strings.push(chunk.subarray(start, start + bytes.value).toString('utf8'));
    } else {
      const chars = readUleb128(chunk, pos);
      const start = chars.next;
      strings.push(chunk.subarray(start, start + chars.value * 2).toString('utf16le'));
    }
  }

  const rawStyles = styleCount > 0 && stylesStart > 0 ? chunk.subarray(stylesStart) : Buffer.alloc(0);

  return { strings, utf8, sorted, rawStyles, styleCount, stylesStart, chunkSize };
}

function encodeStringPool(pool: StringPool, replacements: Map<number, string>): Buffer {
  const values = pool.strings.slice();
  for (const [index, value] of replacements) {
    if (index >= 0 && index < values.length) values[index] = value;
  }

  // 依次编码字符串数据，同时记录每个字符串相对 stringsStart 的偏移
  const encoded: Buffer[] = [];
  const offsets: number[] = [];
  let cursor = 0;
  for (const value of values) {
    offsets.push(cursor);
    let piece: Buffer;
    if (pool.utf8) {
      const bytes = Buffer.from(value, 'utf8');
      const charLen = value.length; // UTF-16 码元数量，AXML 约定
      piece = Buffer.concat([writeUleb128(charLen), writeUleb128(bytes.length), bytes, Buffer.from([0])]);
    } else {
      const bytes = Buffer.from(value, 'utf16le');
      piece = Buffer.concat([writeUleb128(value.length), bytes, Buffer.from([0, 0])]);
    }
    encoded.push(piece);
    cursor += piece.length;
  }
  const stringData = Buffer.concat(encoded);

  const headerSize = 28;
  const stringsStart = headerSize + offsets.length * 4 + pool.styleCount * 4;
  const stylesStart = pool.styleCount > 0 ? align4(stringsStart + stringData.length) : 0;

  const body = Buffer.concat([
    ...offsets.map((o) => {
      const b = Buffer.alloc(4);
      b.writeUInt32LE(o, 0);
      return b;
    }),
    stringData,
  ]);

  let size = stringsStart + stringData.length;
  let styleTail = Buffer.alloc(0);
  if (pool.styleCount > 0 && pool.rawStyles.length > 0) {
    const padding = stylesStart - size;
    styleTail = Buffer.concat([Buffer.alloc(Math.max(padding, 0)), pool.rawStyles]);
    size = stylesStart + pool.rawStyles.length;
  }

  const aligned = align4(size);
  const out = Buffer.alloc(aligned);
  out.writeUInt16LE(CHUNK_STRING_POOL, 0);
  out.writeUInt16LE(headerSize, 2);
  out.writeUInt32LE(aligned, 4);
  out.writeUInt32LE(offsets.length, 8);
  out.writeUInt32LE(pool.styleCount, 12);
  // 保留 UTF-8 标记，去掉 sorted 标记（顺序可能已被破坏）
  out.writeUInt32LE(pool.utf8 ? 0x100 : 0, 16);
  out.writeUInt32LE(stringsStart, 20);
  out.writeUInt32LE(stylesStart, 24);
  body.copy(out, headerSize);
  if (styleTail.length > 0) styleTail.copy(out, size - styleTail.length);
  return out;
}

function align4(n: number): number {
  return (n + 3) & ~3;
}

/** 解析 AXML 的全部元素（按出现顺序，含嵌套深度） */
export function parseAxmlElements(buf: Buffer): AxmlElement[] {
  if (buf.length < 8 || buf.readUInt16LE(0) !== CHUNK_XML) {
    throw new Error('不是二进制 AXML 文件');
  }

  const elements: AxmlElement[] = [];
  let pos = buf.readUInt16LE(2);
  let pool: StringPool | null = null;
  let depth = 0;
  const total = buf.length;

  while (pos + 8 <= total) {
    const type = buf.readUInt16LE(pos);
    const chunkSize = buf.readUInt32LE(pos + 4);
    if (chunkSize <= 0 || pos + chunkSize > total) break;

    if (type === CHUNK_STRING_POOL) {
      pool = parseStringPool(buf.subarray(pos, pos + chunkSize));
    } else if (type === CHUNK_START_ELEMENT) {
      const str = (idx: number): string | null => {
        if (!pool || idx === NO_INDEX || idx >= pool.strings.length) return null;
        return pool.strings[idx];
      };
      const nameIdx = buf.readUInt32LE(pos + 16 + 4);
      const attrStart = buf.readUInt16LE(pos + 16 + 8);
      const attrCount = buf.readUInt16LE(pos + 16 + 12);
      const attrsBase = pos + 16 + attrStart;
      const attributes: AxmlAttribute[] = [];
      for (let i = 0; i < attrCount; i++) {
        const a = attrsBase + i * 20;
        if (a + 20 > total) break;
        const nsIdx = buf.readUInt32LE(a);
        const aNameIdx = buf.readUInt32LE(a + 4);
        const rawIdx = buf.readUInt32LE(a + 8);
        const dataType = buf[a + 15];
        const data = buf.readUInt32LE(a + 16);
        let value: string | number | null = null;
        if (dataType === TYPE_STRING) value = str(data);
        else if (dataType === TYPE_INT_DEC || dataType === TYPE_INT_HEX) value = data;
        else if (dataType === TYPE_REFERENCE) value = null;
        attributes.push({
          name: str(aNameIdx) ?? `@${aNameIdx}`,
          namespace: nsIdx === NO_INDEX ? null : str(nsIdx),
          rawValue: rawIdx === NO_INDEX ? null : str(rawIdx),
          value,
          type: dataType,
        });
      }
      elements.push({ name: str(nameIdx) ?? '?', attributes, depth });
      depth++;
    } else if (type === CHUNK_END_ELEMENT) {
      depth = Math.max(0, depth - 1);
    }

    pos += chunkSize;
  }

  return elements;
}

function attrValue(el: AxmlElement, name: string): string | number | null {
  const found = el.attributes.find((a) => a.name === name);
  return found ? found.value : null;
}

function attrString(el: AxmlElement | undefined, name: string): string | undefined {
  if (!el) return undefined;
  const value = attrValue(el, name);
  return typeof value === 'string' ? value : undefined;
}

/** 读取清单关键信息（只读，不修改） */
export function readManifest(buf: Buffer): ManifestInfo {
  const isBinary = buf.length >= 8 && buf.readUInt16LE(0) === CHUNK_XML;
  if (!isBinary) {
    const text = buf.toString('utf8');
    const pkg = /<manifest[^>]*\bpackage\s*=\s*"([^"]*)"/i.exec(text);
    const vName = /android:versionName\s*=\s*"([^"]*)"/i.exec(text);
    const vCode = /android:versionCode\s*=\s*"([^"]*)"/i.exec(text);
    return {
      isBinary: false,
      packageName: pkg?.[1] ?? '',
      versionName: vName?.[1],
      versionCode: vCode ? Number(vCode[1]) : undefined,
      componentNames: [],
    };
  }

  const elements = parseAxmlElements(buf);
  const manifestEl = elements.find((e) => e.name === 'manifest');
  const usesSdk = elements.find((e) => e.name === 'uses-sdk');
  const application = elements.find((e) => e.name === 'application');

  const componentNames: string[] = [];
  for (const el of elements) {
    const n = attrValue(el, 'name');
    if (typeof n === 'string') componentNames.push(n);
  }

  const versionCodeRaw = manifestEl ? attrValue(manifestEl, 'versionCode') : null;
  const minSdkRaw = usesSdk ? attrValue(usesSdk, 'minSdkVersion') : null;
  const targetSdkRaw = usesSdk ? attrValue(usesSdk, 'targetSdkVersion') : null;

  return {
    isBinary: true,
    packageName: attrString(manifestEl, 'package') ?? '',
    versionName: attrString(manifestEl, 'versionName'),
    versionCode: typeof versionCodeRaw === 'number' ? versionCodeRaw : undefined,
    minSdk: typeof minSdkRaw === 'number' ? minSdkRaw : undefined,
    targetSdk: typeof targetSdkRaw === 'number' ? targetSdkRaw : undefined,
    compileSdk: attrString(manifestEl, 'platformBuildVersionName'),
    applicationName: attrString(application, 'name'),
    mainActivity: attrString(
      elements.find((e) => e.name === 'activity'),
      'name',
    ),
    componentNames,
  };
}

/**
 * 精确查找 <manifest> 元素上 package 属性所引用的字符串下标。
 * 做法：先解析出字符串池，在池中找到名为 "package" 的字符串下标，
 * 再在 <manifest> 的属性中匹配「属性名索引 == 该下标」的那一项。
 */
export function findManifestPackageIndex(buf: Buffer): number {
  if (buf.length < 8 || buf.readUInt16LE(0) !== CHUNK_XML) {
    throw new Error('不是二进制 AXML 文件，无法定位 package 属性');
  }
  const total = buf.length;
  const headerSize = buf.readUInt16LE(2);
  const poolChunkSize = buf.readUInt32LE(headerSize + 4);
  const pool = parseStringPool(buf.subarray(headerSize, headerSize + poolChunkSize));
  const nameIndexes = pool.strings
    .map((s, i) => (s === 'package' ? i : -1))
    .filter((i) => i >= 0);
  if (nameIndexes.length === 0) throw new Error('清单字符串池中找不到 package 属性名');

  let pos = headerSize + poolChunkSize;
  while (pos + 8 <= total) {
    const type = buf.readUInt16LE(pos);
    const chunkSize = buf.readUInt32LE(pos + 4);
    if (chunkSize <= 0 || pos + chunkSize > total) break;
    if (type === CHUNK_START_ELEMENT) {
      const attrStart = buf.readUInt16LE(pos + 16 + 8);
      const attrCount = buf.readUInt16LE(pos + 16 + 12);
      const attrsBase = pos + 16 + attrStart;
      for (let i = 0; i < attrCount; i++) {
        const a = attrsBase + i * 20;
        if (a + 20 > total) break;
        const nameIdx = buf.readUInt32LE(a + 4);
        if (nameIndexes.includes(nameIdx)) {
          const dataType = buf[a + 15];
          if (dataType !== TYPE_STRING) {
            throw new Error('清单中的 package 属性不是字符串类型，无法替换');
          }
          return buf.readUInt32LE(a + 16);
        }
      }
      break; // 只看 <manifest>
    }
    pos += chunkSize;
  }
  throw new Error('未能在 <manifest> 上找到 package 属性');
}

/** 需要跟随包名一起改写的属性组合；element 为 null 表示匹配任意元素 */
const PACKAGE_DERIVED_ATTRIBUTES: Array<{ element: string | null; attribute: string }> = [
  { element: 'permission', attribute: 'name' },
  { element: 'uses-permission', attribute: 'name' },
  { element: 'uses-permission-sdk-23', attribute: 'name' },
  { element: 'provider', attribute: 'authorities' },
  { element: null, attribute: 'permission' },
  { element: null, attribute: 'readPermission' },
  { element: null, attribute: 'writePermission' },
];

function isPackageDerived(elementName: string, attributeName: string): boolean {
  return PACKAGE_DERIVED_ATTRIBUTES.some(
    (rule) => (rule.element === null || rule.element === elementName) && rule.attribute === attributeName,
  );
}

/** 把以 oldPackage. 开头的标识符换成新前缀；authorities 可能是分号分隔的多个值 */
function rewritePrefixed(value: string, oldPackage: string, newPackage: string): string {
  const prefix = `${oldPackage}.`;
  return value
    .split(';')
    .map((part) => (part.startsWith(prefix) ? `${newPackage}${part.slice(oldPackage.length)}` : part))
    .join(';');
}

/**
 * 收集清单中所有「自定义权限名 / Provider 授权名」属性的字符串池下标
 * （仅包含以旧包名为前缀的值）。
 */
export function collectPackageDerivedIndices(buf: Buffer, oldPackage: string): number[] {
  if (buf.length < 8 || buf.readUInt16LE(0) !== CHUNK_XML) return [];
  const headerSize = buf.readUInt16LE(2);
  const poolChunkSize = buf.readUInt32LE(headerSize + 4);
  const pool = parseStringPool(buf.subarray(headerSize, headerSize + poolChunkSize));
  const indices: number[] = [];
  const total = buf.length;
  let pos = headerSize + poolChunkSize;

  while (pos + 8 <= total) {
    const type = buf.readUInt16LE(pos);
    const chunkSize = buf.readUInt32LE(pos + 4);
    if (chunkSize <= 0 || pos + chunkSize > total) break;
    if (type === CHUNK_START_ELEMENT) {
      const elementName = pool.strings[buf.readUInt32LE(pos + 20)] ?? '';
      const attrStart = buf.readUInt16LE(pos + 24);
      const attrCount = buf.readUInt16LE(pos + 28);
      const attrsBase = pos + 16 + attrStart;
      for (let i = 0; i < attrCount; i++) {
        const a = attrsBase + i * 20;
        if (a + 20 > total) break;
        if (buf[a + 15] !== TYPE_STRING) continue;
        const attributeName = pool.strings[buf.readUInt32LE(a + 4)] ?? '';
        if (!isPackageDerived(elementName, attributeName)) continue;
        const valueIndex = buf.readUInt32LE(a + 16);
        const value = pool.strings[valueIndex];
        if (typeof value === 'string' && value.startsWith(`${oldPackage}.`)) indices.push(valueIndex);
      }
    }
    pos += chunkSize;
  }
  return indices;
}

/** 纯文本清单的等价改写（仅在 AndroidManifest 未被编译时才会走到） */
function rewriteTextIdentifiers(source: string, oldPackage: string, newPackage: string): string {
  const rewrite = (value: string): string => rewritePrefixed(value, oldPackage, newPackage);
  return source
    .replace(
      /(<(?:permission|uses-permission|uses-permission-sdk-23)\b[^>]*?\bandroid:name\s*=\s*")([^"]*)(")/g,
      (_m, p1: string, value: string, p3: string) => `${p1}${rewrite(value)}${p3}`,
    )
    .replace(
      /(<provider\b[^>]*?\bandroid:authorities\s*=\s*")([^"]*)(")/g,
      (_m, p1: string, value: string, p3: string) => `${p1}${rewrite(value)}${p3}`,
    )
    .replace(
      /(\bandroid:(?:permission|readPermission|writePermission)\s*=\s*")([^"]*)(")/g,
      (_m, p1: string, value: string, p3: string) => `${p1}${rewrite(value)}${p3}`,
    );
}

/** 导出字符串池的全部字符串（调试/比对用） */
export function dumpStringPool(buf: Buffer): string[] {
  if (buf.length < 8 || buf.readUInt16LE(0) !== CHUNK_XML) return [];
  const headerSize = buf.readUInt16LE(2);
  const poolChunkSize = buf.readUInt32LE(headerSize + 4);
  return parseStringPool(buf.subarray(headerSize, headerSize + poolChunkSize)).strings;
}

/**
 * 替换包名。同样长度或不同长度都可处理（重建字符串池）。
 * 返回新的 AndroidManifest.xml 字节；非 AXML（纯文本）时退化为文本替换。
 *
 * rewriteIdentifiers = true 时，还会把清单中**以旧包名为前缀**的自定义标识符一起改写，
 * 例如：
 *   <permission android:name="old.permission.C2D_MESSAGE">
 *   <uses-permission android:name="old.permission.C2D_MESSAGE">
 *   <provider android:authorities="old.provider">
 * 因为这类标识符全局唯一，若不改写，新旧两个包同时安装会分别报
 * INSTALL_FAILED_DUPLICATE_PERMISSION / INSTALL_FAILED_CONFLICTING_PROVIDER。
 *
 * 注意：只改写「权限名 / 授权名」这类属性，**不会**动 android:name 上的组件类名，
 * 否则会把类引用改坏。
 */
export function setPackageName(
  buf: Buffer,
  newPackage: string,
  options: { rewriteIdentifiers?: boolean } = {},
): Buffer {
  const isBinary = buf.length >= 8 && buf.readUInt16LE(0) === CHUNK_XML;
  if (!isBinary) {
    const text = buf.toString('utf8');
    let replaced = text.replace(/(<manifest[^>]*\bpackage\s*=\s*")([^"]*)(")/i, `$1${newPackage}$3`);
    if (options.rewriteIdentifiers) {
      const oldPackage = /<manifest[^>]*\bpackage\s*=\s*"([^"]*)"/i.exec(text)?.[1] ?? '';
      if (oldPackage) replaced = rewriteTextIdentifiers(replaced, oldPackage, newPackage);
    }
    return Buffer.from(replaced, 'utf8');
  }

  const xmlHeaderSize = buf.readUInt16LE(2);
  const poolChunkSize = buf.readUInt32LE(xmlHeaderSize + 4);
  const pool = parseStringPool(buf.subarray(xmlHeaderSize, xmlHeaderSize + poolChunkSize));
  const stringIndex = findManifestPackageIndex(buf);
  const oldPackage = pool.strings[stringIndex] ?? '';

  const replacements = new Map<number, string>();
  replacements.set(stringIndex, newPackage);
  if (options.rewriteIdentifiers && oldPackage && oldPackage !== newPackage) {
    for (const index of collectPackageDerivedIndices(buf, oldPackage)) {
      if (index === stringIndex) continue;
      const value = pool.strings[index];
      if (typeof value !== 'string') continue;
      const next = rewritePrefixed(value, oldPackage, newPackage);
      if (next !== value) replacements.set(index, next);
    }
  }

  const newPool = encodeStringPool(pool, replacements);
  const rest = buf.subarray(xmlHeaderSize + poolChunkSize);

  const out = Buffer.alloc(8 + newPool.length + rest.length);
  out.writeUInt16LE(CHUNK_XML, 0);
  out.writeUInt16LE(8, 2);
  out.writeUInt32LE(out.length, 4);
  newPool.copy(out, 8);
  rest.copy(out, 8 + newPool.length);
  return out;
}
