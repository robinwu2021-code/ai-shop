/**
 * 环境变量的**声明**与**消费**必须对得上。两个方向都查，因为两边各出过一次事故。
 *
 * ① 模板里声明了，代码里没人读
 *    2026-08-22 起 `b-app/.env.local` 里有 `VITE_AMAP_KEY_ANDROID`（值是对的），
 *    文档《App签名与打包参数》§6 写着「经 manifestPlaceholders 注入 AndroidManifest」——
 *    **而那段注入代码不在任何 build.gradle 里**，随一次重解压 DCloud SDK 没了。
 *    表现：构建成功、装机不报错、界面全对，只有真机点定位报错误码 7（KEY 鉴权失败），
 *    错误文案看不出是没配 key。六天后才发现，靠的是逐个包 apkanalyzer 去数。
 *
 * ② 代码里读了，模板里没声明
 *    同日复核发现 `ops-web/lib/amap.ts` 读的两个高德变量，三份模板里一个都没有。
 *    下一个人照模板配完，地图是一片白框 —— 而它不报错。
 *
 * 为什么用模板而不是 `.env.local`：后者被 gitignore，闸门读不到，且每台机器不一样。
 * 模板进仓库，是这件事唯一可校验的载体。
 *
 * ⚠️ 两条判据都收窄过一次，收窄的理由都写在下面各自的位置 ——
 * 第一版按「字面出现」判，一次报出 34 条，其中 30 条是判据说多了。
 */
import { describe, expect, it } from "vitest";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(__dirname, "../../..");

/** 扫哪些地方算「有人读」。**刻意不含 docs/** —— 文档写了不等于接上了，那正是①的成因 */
const CODE_DIRS = [
  "b-app/src", "b-app/offline", "c-app/src", "ops-web/app", "ops-web/lib",
  "ops-web/components", "packages", "backend/shop-app/src/main", "backend/shop-core/src/main",
  "backend/shop-base/src/main", "scripts", "site",
];

/** 判据②的范围：应用代码，不含构建/测试工具（理由写在那条判据里） */
const APP_DIRS = CODE_DIRS.filter((d) => !d.startsWith("scripts") && d !== "site");

const TEMPLATES = [
  "b-app/.env.local.example",
  "ops-web/.env.local.example",
  "backend/.env.local.example",
];

/**
 * 模板里声明的变量。上一行写了 `# 未接线：<理由>` 的跳过 ——
 * 这类是**故意先占个位**（key 还没申请、那条链路还没做），理由必须当场写下来。
 * 不给这个出口的话，唯一的做法是「先别写进模板」，而那正好回到事故①的状态：
 * 变量存在、没人知道。
 */
function declared(tpl: string): { name: string; unwired: string | null }[] {
  const lines = readFileSync(join(ROOT, tpl), "utf8").split("\n");
  const out: { name: string; unwired: string | null }[] = [];
  lines.forEach((l, i) => {
    const m = /^([A-Z][A-Z0-9_]*)=/.exec(l);
    if (!m) return;
    const prev = (lines[i - 1] ?? "").trim();
    const u = /^#\s*未接线[：:]\s*(.+)$/.exec(prev);
    out.push({ name: m[1], unwired: u ? u[1] : null });
  });
  return out;
}

/** 全树只扫一遍，把命中行原样拿回来。逐个变量各扫一遍要 150 次全树 grep，会超时 */
function grepAll(pattern: string, dirs: string[]): string[] {
  try {
    return execFileSync(
      "grep",
      ["-rIohE", "--exclude-dir=node_modules", "--exclude-dir=dist", "--exclude-dir=.next",
       "--exclude=*.example", pattern, ...dirs.map((d) => join(ROOT, d))],
      { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] },
    ).split("\n").filter(Boolean);
  } catch {
    return []; // grep 没命中时退出码 1
  }
}

/**
 * 代码里**真的被读**的变量名。判「读法」，不判「名字出现过」。
 *
 * 第一版按字面出现判，于是 `verify-apk.sh` 的**报错文案**里那句
 * 「要的那行：AMAP_KEY_ANDROID=<32位>」把它算成了消费方 —— 把注入脚本整个删掉，
 * 判据照样绿，而那正是这条判据要拦的场景。按名字/字面判是这个仓库里反复栽的
 * 同一个跟头，这次栽在闸门自己身上。所以列出各语言里「读一个环境变量」的实际长相：
 */
function readNames(): Set<string> {
  const out = new Set<string>();
  const push = (line: string, strip: RegExp) => {
    const n = line.replace(strip, "").replace(/[:}='"`\s].*$/, "");
    if (/^[A-Z][A-Z0-9_]*$/.test(n)) out.add(n);
  };
  //                                                            前端        yml/gradle   shell   Java        解析 .env 的前缀
  for (const l of grepAll("(import\\.meta\\.env|process\\.env)\\.[A-Z][A-Z0-9_]+", CODE_DIRS)) push(l, /^(import\.meta\.env|process\.env)\./, );
  for (const l of grepAll("\\$\\{[A-Z][A-Z0-9_]+[:}]", CODE_DIRS)) push(l, /^\$\{/);
  for (const l of grepAll("\\$[A-Z][A-Z0-9_]{3,}", CODE_DIRS)) push(l, /^\$/);
  for (const l of grepAll("System\\.getenv\\(\"[A-Z][A-Z0-9_]+", CODE_DIRS)) push(l, /^System\.getenv\("/);
  for (const l of grepAll("['\"`][A-Z][A-Z0-9_]+=", CODE_DIRS)) push(l, /^['"`]/);
  return out;
}

/**
 * Spring 的**松散绑定**：`SHOP_OPS_PASSWORD_DELIVERY` 会自动映射到
 * `shop.ops.password-delivery`，**代码与 yml 里一个字面都不会出现**
 *（`OpsServiceImpl` 的 `@Value("${shop.ops.password-delivery:mail}")` 就是这样）。
 * 第一版不认这条，把它报成孤儿 —— 而它一直在生效。
 */
function springProps(): Set<string> {
  const out = new Set<string>();
  for (const l of grepAll("\\$\\{[a-z][a-z0-9.-]+[:}]", CODE_DIRS)) {
    out.add(l.replace(/^\$\{/, "").replace(/[:}].*$/, "").replace(/-/g, "_").replace(/\./g, "_"));
  }
  return out;
}

describe("环境变量：声明与消费要对得上", () => {
  it("★★★ 模板里声明的变量，代码里必须有人读 —— 声明了没人读 = 配了也不生效", () => {
    const reads = readNames();
    const props = springProps();
    const orphans: string[] = [];
    for (const tpl of TEMPLATES) {
      for (const { name, unwired } of declared(tpl)) {
        if (unwired) continue;
        if (!reads.has(name) && !props.has(name.toLowerCase())) orphans.push(`${tpl} 的 ${name}`);
      }
    }
    expect(
      orphans,
      `这些变量声明了但没有任何消费方（接上，或在模板里那一行上方写「# 未接线：理由」）：\n  ${orphans.join("\n  ")}`,
    ).toEqual([]);
  });

  it("★★★ 前端读的变量，模板或 .env 里必须声明 —— 没声明 = 下一个人不知道要配", () => {
    /*
     * **只查前端**（`import.meta.env.X` / `process.env.X`）。
     * 后端不查是有理由的：`application.yml` 里每一个都写成 `${X:默认值}`，
     * **默认值本身就是文档** —— 不配也能跑，配了是调优。把这 30 个塞进模板只是噪声。
     * 前端没有这个机制：读不到就是 undefined，而症状往往是「白框」「空列表」，不报错。
     */
    const declaredAll = new Set(TEMPLATES.flatMap((t) => declared(t).map((d) => d.name)));
    // 进仓库的 `.env` / `.env.production` 带的是**默认值**，本身就是声明
    for (const f of ["b-app/.env", "c-app/.env", "b-app/.env.production", "c-app/.env.production"]) {
      for (const m of readFileSync(join(ROOT, f), "utf8").matchAll(/^#?\s*([A-Z][A-Z0-9_]*)=/gm)) {
        declaredAll.add(m[1]);
      }
    }
    const used = new Set<string>();
    let out = "";
    try {
      out = execFileSync(
        "grep",
        ["-rIohE", "--exclude-dir=node_modules", "--exclude-dir=dist", "--exclude-dir=.next",
         "--exclude=*.example", "(import\\.meta\\.env|process\\.env)\\.[A-Z][A-Z0-9_]+",
         // 只查**应用代码**：`scripts/` 与 `site/scripts/` 是构建与 e2e 工具，
         // 它们的 E2E_BASE / FORCE_SUBSET 是「跑这条命令时临时给一下」的开关，
         // 不是要写进 `.env.local` 的配置 —— 塞进模板反而误导。
         ...APP_DIRS.map((d) => join(ROOT, d))],
        { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] },
      );
    } catch { /* 没命中 */ }
    for (const line of out.split("\n")) {
      const name = line.replace(/^(import\.meta\.env|process\.env)\./, "");
      // `import.meta.env.VITE_` + 变量拼接出来的半截名字，不是真变量
      // NODE_ENV 是 Node/打包器自己给的，不是本项目的配置
      if (name.length > 5 && !name.endsWith("_") && name !== "NODE_ENV") used.add(name);
    }
    const missing = [...used].filter((n) => !declaredAll.has(n)).sort();
    expect(missing, `这些变量前端在读，但模板与 .env 里都没声明：\n  ${missing.join("\n  ")}`).toEqual([]);
  });
});
