// 自检脚本运行时替身：让 electron/core/paths.ts 能在纯 Node 下工作。
const os = require('node:os');
const path = require('node:path');
const fs = require('node:fs');

const dir = path.join(os.tmpdir(), 'aam-selftest-userdata');
fs.mkdirSync(dir, { recursive: true });

exports.app = {
  getPath: () => dir,
};
