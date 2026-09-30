// 把元器件小程序（独立项目 ai-hxkey 的 app/，原 elec-app/）**临时并进 c-app** 打一包：借虹选好店的登录与域名测一轮。
//
// 元器件是独立工程，将来独立发布（TDD-元器件-小程序独立工程）。测试期它自己的小程序号还没有，
// 主系统也只认虹选好店的 appid —— 于是先当 c-app 的一个分包进去，「我的」里出一个测试入口。
//
// **不改仓库里的任何一个文件的内容**，做法是「构建前拷、构建后还原」：
//   1. ai-hxkey/app/src 的页面、组件、接口、store 拷进 src/pkg-elec/（gitignore）
//      - `@/` 改指 `@/pkg-elec/`（c-app 的 `@/api`、`@/stores/user` 与元器件的同名）
//      - 去掉 `title-key`：c-app 的词条里没有元器件的标题，小程序上用 pages.json 的原生标题
//   2. src/pages.json 临时加一个分包 `pkg-elec` 与 `el-*` 的 easycom 规则，构建完还原
//   3. 注入 VITE_WITH_ELEC=1（「我的」出入口）与 VITE_ELEC_ROUTE_BASE=/pkg-elec（元器件的路由前缀）
//
// ⚠️ **别在共享工作区里跑 release**：发版脚本从当前目录构建，会把别的会话没提交的改动一起传上体验版。
// 在干净的 HEAD 副本里跑（见 TDD §2.4 的命令）。
//
// 用法（在 c-app 目录下）：
//   node scripts/with-elec.mjs build                    # 并包构建小程序到 dist/build/mp-weixin
//   node scripts/with-elec.mjs release <版本> "<备注>"   # 并包构建并传体验版（走 release-mp.sh）
//   node scripts/with-elec.mjs restore                  # 中途被打断时手工还原
import { cpSync, existsSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join, dirname, extname } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const HERE = join(dirname(fileURLToPath(import.meta.url)), "..");
// 元器件小程序 2026-09-30 迁到独立项目 ai-hxkey，与本仓库并排放在 ~/work/ai/ 下
const ELEC = join(HERE, "..", "..", "ai-hxkey", "app", "src");
const PKG = "pkg-elec";
const OUT = join(HERE, "src", PKG);
const PAGES_JSON = join(HERE, "src", "pages.json");
const BACKUP = `${PAGES_JSON}.with-elec.bak`;
/** 元器件自己的 App.vue / main.ts / i18n / manifest 不拷：进程是 c-app 的 */
const COPY = ["pages", "components", "shared", "api", "stores"];

function walk(dir) {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

function prepare() {
  if (!existsSync(ELEC)) throw new Error(`找不到 ${ELEC}`);
  if (existsSync(BACKUP)) throw new Error(`上一次没还原干净（${BACKUP} 还在）：先跑 restore`);
  rmSync(OUT, { recursive: true, force: true });
  for (const d of COPY) cpSync(join(ELEC, d), join(OUT, d), { recursive: true });

  let rewritten = 0;
  for (const f of walk(OUT)) {
    if (![".ts", ".vue"].includes(extname(f))) continue;
    const src = readFileSync(f, "utf8");
    const out = src
      .replace(/(from\s+["'])@\//g, `$1@/${PKG}/`)
      .replace(/\s+title-key="title\.[A-Za-z]+"/g, "");
    if (out !== src) {
      writeFileSync(f, out);
      rewritten++;
    }
  }
  // 改完还留着 `@/` 而不是 `@/pkg-elec/` 的导入 = 会静默解析到 c-app 的同名模块。**必须为零**
  const leaks = walk(OUT).filter((f) => /from\s+["']@\/(?!pkg-elec\/)/.test(readFileSync(f, "utf8")));
  if (leaks.length) throw new Error(`这些文件里还有指向 c-app 的 @/ 导入：\n  ${leaks.join("\n  ")}`);

  const raw = readFileSync(PAGES_JSON, "utf8");
  writeFileSync(BACKUP, raw);
  const app = JSON.parse(raw);
  const elec = JSON.parse(readFileSync(join(ELEC, "pages.json"), "utf8"));
  app.subPackages = [...(app.subPackages ?? []), { root: PKG, pages: elec.pages }];
  app.easycom.custom["^el-(.*)"] = `@/${PKG}/components/el-$1.vue`;
  writeFileSync(PAGES_JSON, JSON.stringify(app, null, 2) + "\n");
  console.log(`✓ 并包：${elec.pages.length} 页 → ${PKG}/，改写 ${rewritten} 个文件的导入与标题`);
}

function restore() {
  if (existsSync(BACKUP)) {
    writeFileSync(PAGES_JSON, readFileSync(BACKUP, "utf8"));
    rmSync(BACKUP);
  }
  rmSync(OUT, { recursive: true, force: true });
  console.log("✓ 已还原 pages.json，删掉 src/pkg-elec");
}

function run(cmd, args) {
  const r = spawnSync(cmd, args, {
    cwd: HERE,
    stdio: "inherit",
    env: { ...process.env, VITE_WITH_ELEC: "1", VITE_ELEC_ROUTE_BASE: `/${PKG}` },
  });
  return r.status ?? 1;
}

const [cmd, ...rest] = process.argv.slice(2);
if (cmd === "restore") {
  restore();
  process.exit(0);
}
if (cmd !== "build" && cmd !== "release") {
  console.error("用法：node scripts/with-elec.mjs build | release <版本> \"<备注>\" | restore");
  process.exit(2);
}

let code = 1;
try {
  prepare();
  code = cmd === "build"
    ? run("npm", ["run", "build:mp-weixin"])
    : run("bash", ["scripts/release-mp.sh", ...rest]);
} catch (e) {
  console.error(`✗ ${e.message}`);
} finally {
  restore();
}
process.exit(code);
