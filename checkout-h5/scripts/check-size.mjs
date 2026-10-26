// 首屏预算（TDD-支付服务-I5接入层 F35）：dist 里全部 JS + CSS 压缩后合计 < 100KB。
// 收银台在顾客的弱网 4G 上打开，多一百 KB 就是多一秒白屏 —— 预算写成闸门，不靠自觉
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { gzipSync } from "node:zlib";
import { fileURLToPath } from "node:url";

const DIST = join(dirname(fileURLToPath(import.meta.url)), "..", "dist");
const BUDGET = 100 * 1024;

function walk(dir) {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

const files = walk(DIST).filter((p) => /\.(js|css|html)$/.test(p));
let total = 0;
for (const f of files) {
  const gz = gzipSync(readFileSync(f)).length;
  total += gz;
  console.log(`  ${(gz / 1024).toFixed(1).padStart(6)} KB  ${f.slice(DIST.length + 1)}`);
}
console.log(`${total <= BUDGET ? "✓" : "✗"} 首屏合计 ${(total / 1024).toFixed(1)} KB（预算 ${BUDGET / 1024} KB，gzip）`);
process.exit(total <= BUDGET ? 0 : 1);
