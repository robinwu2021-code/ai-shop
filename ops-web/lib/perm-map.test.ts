// UI 能力码 → 后端权限码的映射表守卫。
//
// 这张表是「前端以为有的能力」与「后端真有的能力」之间唯一的连接点。
// 它错一个字的表现都一样：**按钮神秘消失** —— 而那是最难查的一类，
// 因为没有报错、没有网络请求、控制台干干净净。
//
// 三种错各查一条：
//   ① 页面用了某个码，表里没有   → 判 false，入口消失
//   ② 表里映射到一个不存在的后端码 → 永远判 false，同样消失
//   ③ 前端角色表与后端的角色权限配置漂移 → 守卫报假警报，
//      而假警报会让人去删掉本来是好的功能
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { UI_PERM_MAP, UNIMPLEMENTED } from "./perm-map";
import { BACKEND_ROLE_PERMS, can } from "./permissions";

const ROOT = join(import.meta.dirname, "../..");
const PERMS_JAVA = join(ROOT, "backend/shop-base/src/main/java/ai/neargo/shop/auth/Perms.java");

/** 从 Java 源码抽后端权限码常量 —— 与 BizEndpointPermTest 同一手法 */
function backendCodes(): Set<string> {
  if (!existsSync(PERMS_JAVA)) return new Set();
  const src = readFileSync(PERMS_JAVA, "utf8");
  const out = new Set<string>();
  for (const m of src.matchAll(/String\s+[A-Z_]+\s*=\s*"([a-z][a-z:_-]*)"/g)) out.add(m[1]!);
  return out;
}

/** 页面与组件里实际调用的 UI 码 */
function usedUiCodes(): Set<string> {
  const out = new Set<string>();
  const walk = (dir: string) => {
    if (!existsSync(dir)) return;
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) { walk(p); continue; }
      if (!/\.tsx?$/.test(e.name) || e.name.endsWith(".test.ts")) continue;
      const src = readFileSync(p, "utf8");
      /*
       * **两种写法都要认。** 这条正则原先只写 `allow(` —— 那是 useCan 文档里
       * 示例用的名字（`const allow = useCan()`），而真实代码里一半的调用点
       * 写的是 `const can = useCan()`。于是闸门对每一个 `can(` 调用**全盲**。
       * 2026-08-29 实测：放宽后立刻多捞出一个未登记的码，而它造成的正是
       * 本文件开头警告的那件事 —— 按钮神秘消失，零报错。
       * `\b` 挡住 `canModule(`：它的 `can` 后面跟的是字母不是括号。
       */
      for (const m of src.matchAll(/\b(?:allow|can)\(\s*"([^"]+)"\s*\)/g)) out.add(m[1]!);
      // nav.ts 的叶子权限码是数据而非调用
      for (const m of src.matchAll(/\bperm:\s*"([^"]+)"/g)) out.add(m[1]!);
    }
  };
  walk(join(ROOT, "ops-web/app"));
  walk(join(ROOT, "ops-web/components"));
  const navSrc = join(ROOT, "ops-web/lib/nav.ts");
  if (existsSync(navSrc)) {
    for (const m of readFileSync(navSrc, "utf8").matchAll(/\bperm:\s*"([^"]+)"/g)) out.add(m[1]!);
  }
  return out;
}

describe("UI 权限码映射表", () => {
  it("解析没失效 —— 扫不到码时不能静默通过", () => {
    expect(usedUiCodes().size, "一个 UI 码都没扫到，正则或目录变了？").toBeGreaterThan(20);
    expect(backendCodes().size, "一个后端码都没抽到，Perms.java 的写法变了？").toBeGreaterThan(8);
  });

  it("★★★ 页面用到的每个码都要在映射表里 —— 漏一个就是一个消失的按钮", () => {
    const missing = [...usedUiCodes()].filter((c) => !(c in UI_PERM_MAP)).sort();
    expect(
      missing,
      "这些 UI 码没登记进 UI_PERM_MAP，运行时按无权限处理 —— **入口会消失**，\n" +
        "  而且没有任何报错。加进表里：能对上后端某个码就写它，\n" +
        "  后端还没有这块能力就写 UNIMPLEMENTED（那也是一条信息，不是占位）。",
    ).toEqual([]);
  });

  it("★★★ 映射到的后端码必须真的存在于 Perms.java —— 手滑一个字母等于永远拒绝", () => {
    const known = backendCodes();
    if (!known.size) return; // 只装前端的场景
    const bad = Object.entries(UI_PERM_MAP)
      .filter(([, v]) => v !== UNIMPLEMENTED && !known.has(v as string))
      .map(([k, v]) => `${k} → ${String(v)}`)
      .sort();
    expect(
      bad,
      "这些后端码在 Perms.java 里不存在。写错一个字母（order:veiw）的表现\n" +
        "  与「没权限」一模一样：按钮消失，没有报错。",
    ).toEqual([]);
  });

  it("★★ 前端的角色权限镜像必须与后端一致 —— 漂了会报假警报", () => {
    if (!existsSync(PERMS_JAVA)) return;
    const src = readFileSync(PERMS_JAVA, "utf8");
    // 后端角色码 → ops-web 角色码（toOpsRole 翻译过一次，这里反向对上）
    // 只有这三个是异名同义（历史遗留）；后来补的七个两边同名，不再起别名
    const ROLE_ALIAS: Record<string, string> = {
      BD: "MERCHANT_BD", GOODS_OPS: "PRODUCT_OPS", SUPPORT: "CS",
    };
    const at = src.indexOf("ROLE_PERMS = Map.");
    // 找不到就等于这条守卫静默失效：slice(-1) 扫不出任何角色，drift 恒为空。
    // 后端把 Map.of 换成 Map.ofEntries 时就差点这样过去了
    expect(at, "在 Perms.java 里找不到 ROLE_PERMS —— 写法变了，这条守卫已经查不到东西").toBeGreaterThan(0);
    const block = src.slice(at);
    const drift: string[] = [];
    for (const m of block.matchAll(/"([A-Z_]+)",\s*List\.of\(([^)]*)\)/g)) {
      // 认不出的角色**按同名处理**，而不是跳过 ——
      // 跳过意味着后端新加的角色永远不被比对，而那正是最需要比对的时候
      const ui = ROLE_ALIAS[m[1]!] ?? m[1]!;
      // List.of 里既有字面量也有常量引用，都归一成码本身
      const codes = new Set<string>();
      // `*`（超管通配）不符合 [a-z] 开头那条，单独认 —— 漏了它会报一条永远修不掉的漂移
      for (const lit of m[2]!.matchAll(/"(\*|[a-z][a-z:_-]*)"|\b([A-Z][A-Z_]+)\b/g)) {
        if (lit[1]) codes.add(lit[1]);
        else if (lit[2]) {
          const c = src.match(new RegExp(`String\\s+${lit[2]}\\s*=\\s*"([^"]+)"`));
          if (c) codes.add(c[1]!);
        }
      }
      const mine = new Set(BACKEND_ROLE_PERMS[ui] ?? []);
      const onlyBackend = [...codes].filter((c) => !mine.has(c));
      const onlyFront = [...mine].filter((c) => !codes.has(c));
      if (onlyBackend.length || onlyFront.length) {
        drift.push(`${ui}：后端多 [${onlyBackend}]，前端多 [${onlyFront}]`);
      }
    }
    expect(
      drift.sort(),
      "BACKEND_ROLE_PERMS 与后端 Perms.ROLE_PERMS 漂了。\n" +
        "  这份镜像只在没有登录态的地方用（mock 登录、导航守卫的断言），\n" +
        "  漂了的后果是**假警报**：守卫说某个菜单谁都看不见，而实际上有人能看见 ——\n" +
        "  照着它去删功能就删错了。",
    ).toEqual([]);
  });

  it("★★ 后端配了的角色，前端必须都认得 —— 认不出会静默落到最小权限", () => {
    if (!existsSync(PERMS_JAVA)) return;
    const src = readFileSync(PERMS_JAVA, "utf8");
    const at = src.indexOf("ROLE_PERMS = Map.");
    const backendRoles = [...src.slice(at).matchAll(/"([A-Z_]+)",\s*List\.of\(/g)].map((m) => m[1]!);
    expect(backendRoles.length, "一个后端角色都没抽到").toBeGreaterThan(3);
    const ALIAS: Record<string, string> = { BD: "MERCHANT_BD", GOODS_OPS: "PRODUCT_OPS", SUPPORT: "CS" };
    const unknown = backendRoles
      .map((r) => ALIAS[r] ?? r)
      .filter((r) => !(r in BACKEND_ROLE_PERMS))
      .sort();
    expect(
      unknown,
      "后端有这些角色，ops-web 的 BACKEND_ROLE_PERMS 里没有。\n" +
        "  后果是**静默降级**：这个角色的人登录后 toOpsRole 认不出他，\n" +
        "  落到最小权限 ANALYST —— 他看到的是一个几乎空的后台，\n" +
        "  而登录成功了、没有任何报错。同时要在 https/dashboard.ts 的\n" +
        "  BACKEND_ROLE 里加上翻译，两处缺一不可。",
    ).toEqual([]);
  });
});

describe("can() 的语义", () => {
  it("超管通配", () => expect(can(["*"], "merchant:apply:audit")).toBe(true));

  it("没登录 / 空 perms 一律 false", () => {
    expect(can(undefined, "merchant:apply:audit")).toBe(false);
    expect(can([], "merchant:apply:audit")).toBe(false);
  });

  it("★★ 恒等映射之后，UI 码就是后端码", () => {
    /*
     * 2026-08-12 权限码细化之后，后端采纳了这份 UI 码表，大部分是恒等映射。
     * 这条断言此前写的是 `can(["merchant:audit"], "merchant:apply:audit") === true`
     * —— 那个粗码已经不存在了。
     */
    expect(can(["merchant:apply:audit"], "merchant:apply:audit")).toBe(true);
    // 已经不存在的旧粗码不该再放行任何东西
    expect(can(["merchant:audit"], "merchant:apply:audit")).toBe(false);
  });

  it("★★ 仍需翻译的那几条：界面功能没有独立端点，映到覆盖它的码", () => {
    // 掉单补偿/关单策略配置 → 订单干预
    expect(can(["order:order:modify"], "order:pay:repair")).toBe(true);
    expect(can(["order:order:read"], "order:pay:repair")).toBe(false);
  });

  it("★★★ UNIMPLEMENTED 一律 false —— 哪怕是超管", () => {
    /*
     * 后端根本没有这块能力，入口不该存在。显示出来然后点出 404，比藏起来坏得多。
     *
     * ⚠️ 这里的样例码**会随后端补齐而失效**：原先用的是 `risk:rule:update`，
     * 2026-08-13 风控域落地后它不再是 UNIMPLEMENTED，这条测试当场变红 ——
     * 那是对的，它逼着人回来确认「翻标记」这一步真的做了。
     * 换样例时挑一个当下确实还没有后端的码。
     */
    expect(can(["marketing:whatever"], "marketing:member:update")).toBe(false);
    // 超管也一样：那个端点根本不存在，他点下去同样 404
    expect(can(["*"], "marketing:member:update")).toBe(false);
  });

  it("★★ 没登记的码判 false（拒绝优先）—— 默认放行会静默开口子", () => {
    expect(can(["*"], "zz-not-a-real:ui:code")).toBe(false);
  });
});
