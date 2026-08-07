// 把 @ai-shop/ui 软链进**每个 app 自己的 node_modules**。
//
// 为什么不能只靠 npm workspaces 在根目录建的那一份：
// 小程序端编译会按「相对 app 根目录」写出每个组件的产物路径。包在根 node_modules 里，
// 算出来就是 `../node-modules/@ai-shop/ui/...`，rollup 拒绝越出根目录的 chunk 路径，
// 构建直接失败 —— 而 H5 端一切正常，所以这个坑只有真的打小程序包才会踩到。
// 配合 vite 的 `resolve.preserveSymlinks: true`（不解析到 monorepo 里的真实路径）才成立。
//
// npm install 会重建 node_modules，所以这件事挂在 postinstall 上，而不是手工执行一次。
import { mkdirSync, symlinkSync, rmSync, existsSync, lstatSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const APPS = ["c-app", "b-app"];

for (const app of APPS) {
  const dir = join(ROOT, app, "node_modules", "@ai-shop");
  const link = join(dir, "ui");
  mkdirSync(dir, { recursive: true });
  // 已存在的先删：npm 可能放了一份真实目录（拷贝），那会让改动不即时生效
  if (existsSync(link) || lstatSync(link, { throwIfNoEntry: false })) {
    rmSync(link, { recursive: true, force: true });
  }
  symlinkSync(join(ROOT, "packages", "ui"), link, "dir");
  console.log(`linked ${app}/node_modules/@ai-shop/ui -> packages/ui`);
}
