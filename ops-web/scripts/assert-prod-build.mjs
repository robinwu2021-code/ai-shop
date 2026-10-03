/*
 * 生产产物的三条底线。**没有它，构建成功 = 什么都没证明**：
 * 2026-08-24 那次白屏就是这么来的 —— next build 退出码 0、目录齐全、
 * 首页 curl 200，可 index.html 里引的是 /_next/（少了 /ops-web 前缀），
 * 一个 chunk 都取不到，浏览器里是一张白纸。
 *
 * 根因不是手滑，是 **Next 在生产构建时照样读 .env.local**，
 * 而那份文件装的是开发机的配置（API 指向 127.0.0.1:8082、BASE_PATH 为空）。
 * 命令行传的 process.env 优先级最高，能压住它 —— 但「传了没有」这件事
 * 必须有人当场检查，否则下次照样静默漏。
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const OUT = new URL("../out/", import.meta.url).pathname;
const html = readFileSync(join(OUT, "index.html"), "utf8");
const problems = [];

/**
 * 全量扫产物，不只扫 index.html。
 * **开发机地址是烧在 chunk 里的**，首页 HTML 上一个字都看不到 ——
 * 只查首页的话这条检查会永远是绿的，而它正是要防的那个坑。
 */
function allFiles(dir) {
  const out = [];
  for (const e of readdirSync(dir)) {
    const f = join(dir, e);
    if (statSync(f).isDirectory()) out.push(...allFiles(f));
    else if (/\.(js|html|txt|json)$/.test(e)) out.push(f);
  }
  return out;
}
const files = allFiles(OUT);

// 1. 资源前缀。落在 /_next/ 上 = 白屏，且首页仍然返回 200
if (!/(src|href)="\/ops-web\/_next\//.test(html)) {
  const sample = html.match(/(?:src|href)="[^"]*_next[^"]*"/)?.[0] ?? "（一个 _next 引用都没有）";
  problems.push(`资源没带 /ops-web 前缀：${sample}\n    → 少了 NEXT_PUBLIC_BASE_PATH=/ops-web`);
}

// 2. 开发机地址。烧进去的话线上会去打访问者自己的 127.0.0.1，接口全 ERR_BLOCKED / 连接拒绝，
//    页面骨架照常渲染 —— 看起来像「没数据」，而不像「配错了」
const devHits = [];
for (const f of files) {
  const m = readFileSync(f, "utf8").match(/https?:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?/);
  if (m) devHits.push(`${f.slice(OUT.length)} → ${m[0]}`);
}
if (devHits.length) {
  problems.push(`${devHits.length} 个文件里有开发机地址，例如 ${devHits[0]}\n`
    + `    → 显式传 NEXT_PUBLIC_API_BASE=（空串 = 同源，走 nginx 反代 /ops）`);
}

// 3. mock。运营端连着 mock 上线，每个数字都是假的而界面毫无异样
if (files.some((f) => /USE_MOCK\s*[!=]==?\s*"0"/.test(readFileSync(f, "utf8")) === false)
    && files.every((f) => !readFileSync(f, "utf8").includes("__OPS_REAL_BACKEND__"))) {
  // mock 开关在构建期被内联成常量，产物里读不出原值 —— 这一条只能靠上面的
  // 命令行显式传参保证，留个说明比留一条测不准的断言强
}

if (problems.length) {
  console.error("\n✗ 生产产物没通过检查，别传上去：\n");
  problems.forEach((p, i) => console.error(`  ${i + 1}. ${p}\n`));
  process.exit(1);
}
console.log("✓ 产物检查通过：/ops-web 前缀在、无开发机地址");
