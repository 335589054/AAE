import fs from 'node:fs';
import zlib from 'node:zlib';

/**
 * 极简 ZIP 读写器。
 *
 * 设计目标：
 * - 读取 APK 的中央目录，只解压需要的条目（例如 songlist / 单张曲绘），避免全量解包。
 * - 导出时对**未改动**的条目做「原始字节直通拷贝」：不重新压缩，因此即便 APK 有 1GB+
 *   也能在几秒内完成重打包。
 * - 自己完成 zipalign 对齐：所有条目 4 字节对齐；未被压缩的原生库（lib/**.so）按页对齐，
 *   因此不再依赖 build-tools 里的 zipalign。
 */

const SIG_LFH = 0x04034b50;
const SIG_CD = 0x02014b50;
const SIG_EOCD = 0x06054b50;
const SIG_ZIP64_EOCD = 0x06064b50;
const SIG_ZIP64_LOCATOR = 0x07064b50;

/** 未压缩原生库的页对齐字节数（16KB 页设备同样兼容） */
const NATIVE_LIB_ALIGN = 4096;
const DEFAULT_ALIGN = 4;

export interface ZipEntry {
  name: string;
  method: number;
  crc32: number;
  compressedSize: number;
  uncompressedSize: number;
  localHeaderOffset: number;
  dosTime: number;
  dosDate: number;
  externalAttrs: number;
  internalAttrs: number;
  versionMadeBy: number;
  versionNeeded: number;
  flags: number;
  /** 压缩数据在文件中的起始偏移 */
  dataOffset: number;
}

function readUInt64LE(buf: Buffer, offset: number): number {
  const lo = buf.readUInt32LE(offset);
  const hi = buf.readUInt32LE(offset + 4);
  return hi * 0x100000000 + lo;
}

function parseZip64Extra(extra: Buffer, entry: {
  uncompressedSize: number;
  compressedSize: number;
  localHeaderOffset: number;
}): void {
  let p = 0;
  while (p + 4 <= extra.length) {
    const id = extra.readUInt16LE(p);
    const size = extra.readUInt16LE(p + 2);
    const start = p + 4;
    if (start + size > extra.length) break;
    if (id === 0x0001) {
      let q = start;
      if (entry.uncompressedSize === 0xffffffff && q + 8 <= start + size) {
        entry.uncompressedSize = readUInt64LE(extra, q);
        q += 8;
      }
      if (entry.compressedSize === 0xffffffff && q + 8 <= start + size) {
        entry.compressedSize = readUInt64LE(extra, q);
        q += 8;
      }
      if (entry.localHeaderOffset === 0xffffffff && q + 8 <= start + size) {
        entry.localHeaderOffset = readUInt64LE(extra, q);
      }
      return;
    }
    p = start + size;
  }
}

/** 中央目录中保存的「额外字段」原样保留，避免丢失对齐信息 */
export class ZipReader {
  readonly path: string;
  readonly entries = new Map<string, ZipEntry>();
  private readonly fd: number;
  private readonly fileSize: number;

  private constructor(path: string) {
    this.path = path;
    this.fd = fs.openSync(path, 'r');
    this.fileSize = fs.fstatSync(this.fd).size;
    try {
      this.parseCentralDirectory();
    } catch (err) {
      fs.closeSync(this.fd);
      throw err;
    }
  }

  static open(path: string): ZipReader {
    return new ZipReader(path);
  }

  close(): void {
    try {
      fs.closeSync(this.fd);
    } catch {
      /* ignore */
    }
  }

  has(name: string): boolean {
    return this.entries.has(name);
  }

  /** 按前缀列出条目名 */
  list(prefix: string): string[] {
    const out: string[] = [];
    for (const name of this.entries.keys()) {
      if (name.startsWith(prefix)) out.push(name);
    }
    return out;
  }

  listAll(): string[] {
    return [...this.entries.keys()];
  }

  /** 读取并解压条目内容 */
  readFile(name: string): Buffer | null {
    const entry = this.entries.get(name);
    if (!entry) return null;
    const compressed = Buffer.alloc(entry.compressedSize);
    if (entry.compressedSize > 0) {
      fs.readSync(this.fd, compressed, 0, entry.compressedSize, entry.dataOffset);
    }
    if (entry.method === 0) return compressed;
    if (entry.method === 8) return zlib.inflateRawSync(compressed);
    throw new Error(`不支持的压缩方式 ${entry.method}（条目 ${name}）`);
  }

  readText(name: string): string | null {
    const buf = this.readFile(name);
    if (!buf) return null;
    // 去掉可能存在的 UTF-8 BOM
    let text = buf.toString('utf8');
    if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
    return text;
  }

  /** 将某个条目的压缩数据原样拷贝到另一个可写流（直通，不解压） */
  copyEntryRaw(entry: ZipEntry, write: (chunk: Buffer) => void, chunkSize = 4 * 1024 * 1024): void {
    let remaining = entry.compressedSize;
    let position = entry.dataOffset;
    const chunk = Buffer.alloc(Math.min(chunkSize, Math.max(remaining, 1)));
    while (remaining > 0) {
      const toRead = Math.min(chunk.length, remaining);
      const read = fs.readSync(this.fd, chunk, 0, toRead, position);
      if (read <= 0) break;
      write(read === chunk.length ? chunk : chunk.subarray(0, read));
      position += read;
      remaining -= read;
    }
  }

  get size(): number {
    return this.fileSize;
  }
  private readAt(offset: number, length: number): Buffer {
    const buf = Buffer.alloc(length);
    fs.readSync(this.fd, buf, 0, length, offset);
    return buf;
  }

  private parseCentralDirectory(): void {
    const tailLen = Math.min(this.fileSize, 22 + 0xffff);
    const tail = this.readAt(this.fileSize - tailLen, tailLen);

    let eocdPos = -1;
    for (let i = tail.length - 22; i >= 0; i--) {
      if (tail.readUInt32LE(i) === SIG_EOCD) {
        eocdPos = i;
        break;
      }
    }
    if (eocdPos < 0) throw new Error('不是有效的 ZIP/APK 文件：未找到中央目录结束记录');

    let entryCount = tail.readUInt16LE(eocdPos + 10);
    let cdSize = tail.readUInt32LE(eocdPos + 12);
    let cdOffset = tail.readUInt32LE(eocdPos + 16);

    // ZIP64：条目数 / 偏移溢出时读取 ZIP64 结束记录
    if (cdOffset === 0xffffffff || cdSize === 0xffffffff || entryCount === 0xffff) {
      const locatorAbs = this.fileSize - tailLen + eocdPos - 20;
      if (locatorAbs >= 0) {
        const locator = this.readAt(locatorAbs, 20);
        if (locator.readUInt32LE(0) === SIG_ZIP64_LOCATOR) {
          const z64Offset = readUInt64LE(locator, 8);
          const z64 = this.readAt(z64Offset, 56);
          if (z64.readUInt32LE(0) === SIG_ZIP64_EOCD) {
            entryCount = readUInt64LE(z64, 32);
            cdSize = readUInt64LE(z64, 40);
            cdOffset = readUInt64LE(z64, 48);
          }
        }
      }
    }

    if (cdOffset + cdSize > this.fileSize) {
      throw new Error('ZIP 中央目录越界，文件可能已损坏');
    }

    const cd = this.readAt(cdOffset, cdSize);
    let p = 0;
    for (let i = 0; i < entryCount; i++) {
      if (p + 46 > cd.length) break;
      if (cd.readUInt32LE(p) !== SIG_CD) break;

      const flags = cd.readUInt16LE(p + 8);
      const method = cd.readUInt16LE(p + 10);
      const dosTime = cd.readUInt16LE(p + 12);
      const dosDate = cd.readUInt16LE(p + 14);
      const crc = cd.readUInt32LE(p + 16);
      const compressedSize = cd.readUInt32LE(p + 20);
      const uncompressedSize = cd.readUInt32LE(p + 24);
      const nameLen = cd.readUInt16LE(p + 28);
      const extraLen = cd.readUInt16LE(p + 30);
      const commentLen = cd.readUInt16LE(p + 32);
      const internalAttrs = cd.readUInt16LE(p + 36);
      const externalAttrs = cd.readUInt32LE(p + 38);
      const localHeaderOffset = cd.readUInt32LE(p + 42);

      const nameBuf = cd.subarray(p + 46, p + 46 + nameLen);
      const name = nameBuf.toString('utf8');
      const extra = cd.subarray(p + 46 + nameLen, p + 46 + nameLen + extraLen);

      const entry: ZipEntry = {
        name,
        method,
        crc32: crc,
        compressedSize,
        uncompressedSize,
        localHeaderOffset,
        dosTime,
        dosDate,
        externalAttrs,
        internalAttrs,
        versionMadeBy: 20,
        versionNeeded: 20,
        flags,
        dataOffset: 0,
      };
      parseZip64Extra(extra, entry);

      // 读取本地文件头以定位压缩数据起点（本地头的 name/extra 长度可能与中央目录不同）
      if (entry.localHeaderOffset + 30 <= this.fileSize) {
        const lfh = this.readAt(entry.localHeaderOffset, 30);
        if (lfh.readUInt32LE(0) === SIG_LFH) {
          const lNameLen = lfh.readUInt16LE(26);
          const lExtraLen = lfh.readUInt16LE(28);
          entry.dataOffset = entry.localHeaderOffset + 30 + lNameLen + lExtraLen;
        } else {
          entry.dataOffset = entry.localHeaderOffset + 30 + nameLen + extraLen;
        }
      }

      if (!name.endsWith('/')) this.entries.set(name, entry);
      p += 46 + nameLen + extraLen + commentLen;
    }

    if (this.entries.size === 0) {
      throw new Error('ZIP 中没有任何文件条目，无法作为 APK 使用');
    }
  }
}

/* ------------------------------- ZIP 写入 ------------------------------- */

function dosDateTime(d: Date): { dosTime: number; dosDate: number } {
  const dosTime = (d.getHours() << 11) | (d.getMinutes() << 5) | (Math.floor(d.getSeconds() / 2) & 0x1f);
  const dosDate = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
  return { dosTime, dosDate };
}

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[i] = c >>> 0;
  }
  return table;
})();

function fallbackCrc32(buf: Buffer): number {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

const zlibCrc32 = (zlib as unknown as { crc32?: (data: Buffer) => number }).crc32;
export function crc32(buf: Buffer): number {
  return zlibCrc32 ? zlibCrc32(buf) >>> 0 : fallbackCrc32(buf);
}

export interface AddEntryOptions {
  /** true 表示传入的是未压缩数据，需要 deflate；false 表示直通压缩数据 */
  compress?: boolean;
  /** 覆盖条目的压缩方式（直通时使用） */
  method?: number;
  dosTime?: number;
  dosDate?: number;
  externalAttrs?: number;
  align?: number;
}

function isNativeLib(name: string): boolean {
  return name.startsWith('lib/') && name.endsWith('.so');
}

/**
 * 流式 ZIP 写入器：边写边产出，不把整个 APK 读入内存。
 * 默认对所有条目做 4 字节对齐；未压缩的 lib/**.so 做 4096 字节页对齐（等价 zipalign -p）。
 */
export class ZipWriter {
  private readonly fd: number;
  private offset = 0;
  private readonly central: Array<{
    name: string;
    method: number;
    crc32: number;
    compressedSize: number;
    uncompressedSize: number;
    localHeaderOffset: number;
    dosTime: number;
    dosDate: number;
    externalAttrs: number;
  }> = [];

  constructor(private readonly outPath: string) {
    this.fd = fs.openSync(outPath, 'w');
  }

  /** 直通拷贝一个已存在的条目（不重新压缩） */
  addRawEntry(src: ZipReader, entry: ZipEntry, overrides: AddEntryOptions = {}): void {
    const align = overrides.align ?? (entry.method === 0 && isNativeLib(entry.name) ? NATIVE_LIB_ALIGN : DEFAULT_ALIGN);
    const dosTime = overrides.dosTime ?? entry.dosTime;
    const dosDate = overrides.dosDate ?? entry.dosDate;
    const externalAttrs = overrides.externalAttrs ?? entry.externalAttrs;
    const localHeaderOffset = this.writeLocalHeader(entry.name, {
      method: entry.method,
      crc32: entry.crc32,
      compressedSize: entry.compressedSize,
      uncompressedSize: entry.uncompressedSize,
      dosTime,
      dosDate,
      externalAttrs,
      align,
    });
    src.copyEntryRaw(entry, (chunk) => this.write(chunk));
    this.central.push({
      name: entry.name,
      method: entry.method,
      crc32: entry.crc32,
      compressedSize: entry.compressedSize,
      uncompressedSize: entry.uncompressedSize,
      localHeaderOffset,
      dosTime,
      dosDate,
      externalAttrs,
    });
  }

  /** 新增 / 覆盖一个条目 */
  addBuffer(name: string, data: Buffer, opts: AddEntryOptions = {}): void {
    if (name.endsWith('/')) return;
    const compress = opts.compress ?? true;
    const method = opts.method ?? (compress ? 8 : 0);
    const payload = compress ? zlib.deflateRawSync(data, { level: 9 }) : data;
    const checksum = crc32(data);
    const fallbackTime = dosDateTime(new Date());
    const dosTime = opts.dosTime ?? fallbackTime.dosTime;
    const dosDate = opts.dosDate ?? fallbackTime.dosDate;
    const externalAttrs = opts.externalAttrs ?? 0;
    const align = opts.align ?? (method === 0 && isNativeLib(name) ? NATIVE_LIB_ALIGN : DEFAULT_ALIGN);

    const localHeaderOffset = this.writeLocalHeader(name, {
      method,
      crc32: checksum,
      compressedSize: payload.length,
      uncompressedSize: data.length,
      dosTime,
      dosDate,
      externalAttrs,
      align,
    });
    this.write(payload);
    this.central.push({
      name,
      method,
      crc32: checksum,
      compressedSize: payload.length,
      uncompressedSize: data.length,
      localHeaderOffset,
      dosTime,
      dosDate,
      externalAttrs,
    });
  }

  finish(): void {
    const cdStart = this.offset;
    for (const e of this.central) {
      const nameBuf = Buffer.from(e.name, 'utf8');
      const header = Buffer.alloc(46);
      header.writeUInt32LE(SIG_CD, 0);
      header.writeUInt16LE(20, 4); // version made by
      header.writeUInt16LE(20, 6); // version needed
      header.writeUInt16LE(0x800, 8); // UTF-8 名称
      header.writeUInt16LE(e.method, 10);
      header.writeUInt16LE(e.dosTime, 12);
      header.writeUInt16LE(e.dosDate, 14);
      header.writeUInt32LE(e.crc32, 16);
      header.writeUInt32LE(e.compressedSize, 20);
      header.writeUInt32LE(e.uncompressedSize, 24);
      header.writeUInt16LE(nameBuf.length, 28);
      header.writeUInt16LE(0, 30); // extra
      header.writeUInt16LE(0, 32); // comment
      header.writeUInt16LE(0, 34); // disk
      header.writeUInt16LE(0, 36); // internal attrs
      header.writeUInt32LE(e.externalAttrs, 38);
      header.writeUInt32LE(e.localHeaderOffset, 42);
      this.write(header);
      this.write(nameBuf);
    }
    const cdSize = this.offset - cdStart;

    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(SIG_EOCD, 0);
    eocd.writeUInt16LE(0, 4);
    eocd.writeUInt16LE(0, 6);
    eocd.writeUInt16LE(Math.min(this.central.length, 0xffff), 8);
    eocd.writeUInt16LE(Math.min(this.central.length, 0xffff), 10);
    eocd.writeUInt32LE(Math.min(cdSize, 0xffffffff), 12);
    eocd.writeUInt32LE(Math.min(cdStart, 0xffffffff), 16);
    eocd.writeUInt16LE(0, 20);
    this.write(eocd);

    fs.fsyncSync(this.fd);
    fs.closeSync(this.fd);
  }

  abort(): void {
    try {
      fs.closeSync(this.fd);
    } catch {
      /* ignore */
    }
    try {
      fs.unlinkSync(this.outPath);
    } catch {
      /* ignore */
    }
  }

  private write(buf: Buffer): void {
    fs.writeSync(this.fd, buf, 0, buf.length, this.offset);
    this.offset += buf.length;
  }

  private writeLocalHeader(
    name: string,
    info: {
      method: number;
      crc32: number;
      compressedSize: number;
      uncompressedSize: number;
      dosTime: number;
      dosDate: number;
      externalAttrs: number;
      align: number;
    },
  ): number {
    const nameBuf = Buffer.from(name, 'utf8');
    const base = this.offset;

    let pad = (info.align - ((base + 30 + nameBuf.length) % info.align)) % info.align;
    // 额外字段长度必须为 0 或 >= 4，避免写出无法解析的残缺字段
    if (pad > 0 && pad < 4) pad += 4;

    // 本地文件头布局（共 30 字节）：
    // 0 签名 | 4 版本 | 6 标志 | 8 压缩方式 | 10 时间 | 12 日期 |
    // 14 CRC-32 | 18 压缩后大小 | 22 原始大小 | 26 文件名长度 | 28 额外字段长度
    const header = Buffer.alloc(30);
    header.writeUInt32LE(SIG_LFH, 0);
    header.writeUInt16LE(20, 4);
    header.writeUInt16LE(0x800, 6);
    header.writeUInt16LE(info.method, 8);
    header.writeUInt16LE(info.dosTime, 10);
    header.writeUInt16LE(info.dosDate, 12);
    header.writeUInt32LE(info.crc32, 14);
    header.writeUInt32LE(info.compressedSize, 18);
    header.writeUInt32LE(info.uncompressedSize, 22);
    header.writeUInt16LE(nameBuf.length, 26);
    header.writeUInt16LE(pad, 28);
    this.write(header);
    this.write(nameBuf);
    if (pad > 0) this.write(Buffer.alloc(pad));
    return base;
  }
}
