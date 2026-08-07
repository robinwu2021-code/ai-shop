// 两端的 H5 产物 —— **各出各的站点，不合并**：
//
//   dist/web/c/    C 端（消费者）
//   dist/web/b/    B 端（商家）
//
// 曾经把两端合成一个站点（B 端挂 `/m/` 子路径），错在同源：
//   · localStorage 按 origin 隔离，同源就意味着登录态、皮肤、语言、
//     连 mock 的整个「数据库」都变成同一份 —— 商家端读到消费者的订单
//   · 两端的路由路径还完全同名（都有 `#/pages/home/index`），
//     在一个 SPA 会话里来回跳会串页
// 合站省下的那点证书与 CDN 配置，换来的是两端互相污染，不值。
//
// 部署：两个域名（或子域名），各自指向下面两个目录的根，例如
//   shop.example.com   → dist/web/c
//   seller.example.com → dist/web/b
import { execFileSync } from "node:child_process";
import { cpSync, rmSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "dist", "web");

/** @param {string} app @param {string} out */
function build(app, out) {
  console.log(`\n=== 构建 ${app} ===`);
  execFileSync("npm", ["run", "build:h5"], { cwd: join(ROOT, app), stdio: "inherit" });
  cpSync(join(ROOT, app, "dist", "build", "h5"), join(OUT, out), { recursive: true });
}

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

build("c-app", "c");
build("b-app", "b");

console.log(`\n两个独立站点已生成：${OUT}\n  c/  C 端（独立域名）\n  b/  B 端（独立域名）`);
