// 写死左右的 CSS —— 阿语下不会跟着翻。
//
// 三端支持中/英/阿，阿语是 RTL。方向本身是接好的：`applyDirection` 写根节点
// `dir="rtl"`，`base.css` 的 `.sh-root.is-rtl { direction: rtl }` 让 flex 行、
// 文本流、**逻辑**内边距整体镜像，`sh-icon` 的方向性图标另做 `scaleX(-1)`。
//
// **但 `direction: rtl` 管不到写死的物理方向属性**：
// `padding-left`、`margin-right`、`border-left`、`text-align: right`
// 在阿语下原地不动，于是数字列贴错边、强调竖杠留在错的一侧、内边距左右不对称。
//
// **为什么值得一道闸**（2026-08-29 加）：这一类<b>不报错、不崩、闸门全绿</b>，
// 只有真的把语言切成阿语、逐屏看过才会发现 —— 而没人会为了改一行样式去切阿语。
// 当天实测 b-app 有 28 处、packages/ui 3 处，全是这么长出来的。
//
// 判据：`.vue` 的 `<style>` 里不允许出现物理方向属性，写逻辑属性：
//   padding-left  → padding-inline-start      左右一样 → padding-inline
//   margin-right  → margin-inline-end
//   border-left   → border-inline-start
//   text-align: right → text-align: end       left → start
//
// **`left` / `right` 这两个定位属性不在判据里**：它们常与 `transform` 或
// `left: 50%` 的居中写法配对，单独翻一条反而错。要管的话得连成对的那条一起看，
// 那是另一件事。
//
// 用法：
//   node scripts/check-rtl-physical.mjs           # 列出来
//   node scripts/check-rtl-physical.mjs --check   # 超出基线就非零退出
import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const DIRS = ["b-app/src", "c-app/src", "packages/ui/src"];
const BASELINE = join(ROOT, "known-rtl-physical.txt");

const RULES = [
  [/(^|[\s;{])((?:padding|margin|border)-(?:left|right))\s*:/g, (m) => `${m} → ${m.replace(/-(left|right)$/, (_, d) => `-inline-${d === "left" ? "start" : "end"}`)}`],
  [/(^|[\s;{])(text-align)\s*:\s*(left|right)\b/g, null],
];

function styleBlocks(src) {
  // 只看 <style>：模板里的 `text-align` 类名、脚本里的 "left"/"right" 字面量不算
  const out = [];
  for (const m of src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)) {
    out.push({ text: m[1], offset: m.index + m[0].indexOf(m[1]) });
  }
  return out;
}

function scan(file) {
  const src = readFileSync(file, "utf8");
  const hits = [];
  for (const blk of styleBlocks(src)) {
    // 注释里提到 padding-left 是在讲道理，不是在写样式
    const text = blk.text.replace(/\/\*[\s\S]*?\*\//g, (s) => " ".repeat(s.length));
    for (const m of text.matchAll(/(^|[\s;{])((?:padding|margin|border)-(?:left|right)|text-align)\s*:\s*([^;\n]*)/g)) {
      const prop = m[2];
      const val = m[3].trim();
      if (prop === "text-align" && !/^(left|right)\b/.test(val)) continue;
      const line = src.slice(0, blk.offset + m.index).split("\n").length;
      const fix =
        prop === "text-align"
          ? `text-align: ${val.startsWith("left") ? "start" : "end"}`
          : prop.replace(/-(left|right)$/, (_, d) => `-inline-${d === "left" ? "start" : "end"}`);
      hits.push({ file: file.replace(ROOT + "/", ""), line, prop, fix });
    }
  }
  return hits;
}

const files = [];
const walk = (d) => {
  if (!existsSync(d)) return;
  for (const n of readdirSync(d)) {
    if (n === "node_modules" || n === "dist" || n.startsWith(".")) continue;
    const p = join(d, n);
    if (statSync(p).isDirectory()) walk(p);
    else if (n.endsWith(".vue")) files.push(p);
  }
};
DIRS.forEach((d) => walk(join(ROOT, d)));

const hits = files.flatMap(scan);
const known = existsSync(BASELINE)
  ? new Set(
      readFileSync(BASELINE, "utf8")
        .split("\n")
        .map((l) => l.trim())
        .filter((l) => l && !l.startsWith("#")),
    )
  : new Set();

const id = (h) => `${h.file} ${h.prop}`;
const fresh = hits.filter((h) => !known.has(id(h)));

console.log(`.vue 样式里写死左右的 ${hits.length} 处（已知欠账 ${known.size}）　扫描：${files.length} 个组件/页面`);
for (const h of hits) {
  console.log(`   ${fresh.includes(h) ? "★新增" : "     "} ${h.file}:${h.line}  ${h.prop} → ${h.fix}`);
}

const stale = [...known].filter((k) => !hits.some((h) => id(h) === k));
if (stale.length) {
  console.log(`\n✅ 这 ${stale.length} 条已经改成逻辑属性了，把它们从基线里删掉：`);
  for (const k of stale) console.log(`      ${k}`);
}

if (process.argv.includes("--check")) {
  let bad = false;
  if (fresh.length) {
    console.error(`\n✗ 新增了 ${fresh.length} 处写死左右的样式 —— 阿语下它们不会跟着翻。`);
    console.error("  换成逻辑属性（上面每条都写了改成什么），或登记进 known-rtl-physical.txt 并写明为什么。");
    console.error("  ⚠️ 这类问题不报错也不崩：中英文下一切正常，只有切到阿语逐屏看才发现。");
    bad = true;
  }
  if (stale.length) {
    console.error(`\n✗ 基线里有 ${stale.length} 条已经改好了，删掉它们 —— 名单只准变短。`);
    bad = true;
  }
  if (bad) process.exit(1);
}
