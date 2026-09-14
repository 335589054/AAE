/**
 * 比对两份二进制 AndroidManifest.xml（AXML）的差异：
 * 逐元素/属性对比解析结果，并对比字符串池，用来确认包名改写是否只动了预期字段。
 *
 * 用法: npx tsx scripts/diff-manifest.ts <manifestA> <manifestB>
 */
import fs from 'node:fs';
import { dumpStringPool, parseAxmlElements } from '../electron/core/axml';

const [pathA, pathB] = process.argv.slice(2);
if (!pathA || !pathB) {
  console.error('用法: npx tsx scripts/diff-manifest.ts <manifestA> <manifestB>');
  process.exit(2);
}

const bufA = fs.readFileSync(pathA);
const bufB = fs.readFileSync(pathB);

const poolA = dumpStringPool(bufA);
const poolB = dumpStringPool(bufB);
console.log(`字符串池: A=${poolA.length} 条 / B=${poolB.length} 条`);

const onlyA = poolA.filter((s) => !poolB.includes(s));
const onlyB = poolB.filter((s) => !poolA.includes(s));
console.log(`\n=== 只在 A 中的字符串 (${onlyA.length}) ===`);
for (const s of onlyA.slice(0, 40)) console.log(`  ${JSON.stringify(s.slice(0, 120))}`);
console.log(`\n=== 只在 B 中的字符串 (${onlyB.length}) ===`);
for (const s of onlyB.slice(0, 40)) console.log(`  ${JSON.stringify(s.slice(0, 120))}`);

const elsA = parseAxmlElements(bufA);
const elsB = parseAxmlElements(bufB);
console.log(`\n元素数量: A=${elsA.length} / B=${elsB.length}`);

const flat = (els: typeof elsA): Map<string, string> => {
  const map = new Map<string, string>();
  els.forEach((el, index) => {
    for (const attr of el.attributes) {
      map.set(`${index}:<${el.name}>@${attr.name}`, String(attr.value));
    }
  });
  return map;
};

const fa = flat(elsA);
const fb = flat(elsB);
let diff = 0;
for (const [key, value] of fa) {
  const other = fb.get(key);
  if (other === undefined) {
    diff++;
    if (diff <= 30) console.log(`  仅在 A: ${key} = ${JSON.stringify(value)}`);
  } else if (other !== value) {
    diff++;
    console.log(`  不同: ${key}\n     A = ${JSON.stringify(value)}\n     B = ${JSON.stringify(other)}`);
  }
}
for (const [key, value] of fb) {
  if (!fa.has(key)) {
    diff++;
    if (diff <= 30) console.log(`  仅在 B: ${key} = ${JSON.stringify(value)}`);
  }
}
console.log(`\n属性差异合计: ${diff} 处`);
