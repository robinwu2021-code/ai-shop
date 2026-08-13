// 权限码的**人话文案**同样有两个源，而且两边都直接给人看：
//
//   · 后端 `BizPerms.LABELS` —— 70006 的消息用它（「这一步需要「…」的权限」）；
//   · b-app `i18n/locale/*.ts` 的 `perm` 段 —— 授权页上，老板勾的就是这些字。
//
// 对不上的后果不是显示乱码，是**两处说法不一致**：老板在授权页看到一句，
// 员工被拒时看到另一句，而他们要凑在一起弄明白「到底缺什么」。
//
// 更要紧的是文案本身要**说全这个码管什么**。这条守卫是从一次真实的误导来的：
// `biz:stock` 写的是「改库存」，而它实际管着商品 tab 的四个端点 ——
// 列表、详情、改库存、批量改库存。于是两件事同时发生：
//
//   · 授权页上老板勾「改库存」，不知道自己一并给出了「看全部商品与价格」；
//   · 员工点开商品列表被拒，消息说「需要**改库存**的权限」—— 他没想改库存。
//
// 文案对不对没法自动判定，但「有没有文案」与「两处一不一致」可以，这里钉住后者。
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const BIZ_PERMS = join(ROOT, "backend/shop-base/src/main/java/ai/neargo/shop/auth/BizPerms.java");
const LOCALES = ["zh-CN", "en", "ar"] as const;

const javaSrc = readFileSync(BIZ_PERMS, "utf8");

/** `public static final String STOCK = "biz:stock";` → 常量名 → 码 */
function codeConstants(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of javaSrc.matchAll(/String\s+([A-Z_]+)\s*=\s*"(biz:[a-z:]+)"/g)) {
    out[m[1]!] = m[2]!;
  }
  return out;
}

/** `BizPerms.LABELS`：权限码 → 中文一句话 */
function javaLabels(): Record<string, string> {
  const at = javaSrc.indexOf("LABELS = Map.");
  /*
   * 找不到就等于这条守卫静默失效 —— 与 biz-role-seed 里同样的理由：
   * 写法一变，下面的 matchAll 扫不出东西，漂移恒为空，测试永远绿。
   */
  expect(at, "在 BizPerms.java 里找不到 LABELS —— 写法变了，这条守卫已经查不到东西")
    .toBeGreaterThan(0);
  const block = javaSrc.slice(at, javaSrc.indexOf(");", at));
  const consts = codeConstants();
  const out: Record<string, string> = {};
  for (const m of block.matchAll(/Map\.entry\(\s*([A-Z_]+)\s*,\s*"([^"]+)"\s*\)/g)) {
    const code = consts[m[1]!];
    if (code) out[code] = m[2]!;
  }
  return out;
}

/** 某个语言包里 `perm: { … }` 段：权限码 → 文案 */
function localeLabels(locale: string): Record<string, string> {
  const src = readFileSync(join(ROOT, `b-app/src/i18n/locale/${locale}.ts`), "utf8");
  const at = src.indexOf("  perm: {");
  expect(at, `${locale}.ts 里找不到 perm 段`).toBeGreaterThan(0);
  const block = src.slice(at, src.indexOf("},", at));
  const out: Record<string, string> = {};
  for (const m of block.matchAll(/"(biz:[a-z:]+)":\s*"([^"]+)"/g)) {
    out[m[1]!] = m[2]!;
  }
  return out;
}

describe("权限码的人话文案", () => {
  const java = javaLabels();
  const consts = codeConstants();

  it("两个源都解析得到（正则失效时不要静默通过）", () => {
    expect(Object.keys(java).length, "BizPerms.LABELS 解析为空").toBeGreaterThanOrEqual(13);
    expect(Object.keys(localeLabels("zh-CN")).length, "zh-CN 的 perm 段解析为空")
      .toBeGreaterThanOrEqual(13);
  });

  it("★★★ 每个权限码都要有文案 —— 缺一个，70006 就会把裸码甩给用户", () => {
    const codes = new Set(Object.values(consts));
    const missing = [...codes].filter((c) => !java[c]).sort();
    expect(
      missing,
      "这些码在 BizPerms 里定义了，却没进 LABELS ——\n"
        + "  `labelOf` 认不出时原样返回码本身，于是错误消息变成\n"
        + "  「这一步需要「biz:xxx」的权限」。新增码时最容易漏的就是这一步。\n  "
        + missing.join("\n  "),
    ).toEqual([]);
  });

  it.each(LOCALES)("★★ %s 的 perm 段与后端 LABELS 覆盖同一批码", (locale) => {
    const loc = localeLabels(locale);
    const onlyJava = Object.keys(java).filter((c) => !loc[c]).sort();
    const onlyLoc = Object.keys(loc).filter((c) => !java[c]).sort();
    expect(
      { onlyJava, onlyLoc },
      "码集合对不上 ——\n"
        + "  语言包缺码：授权页上那一项直接显示成键名；\n"
        + "  后端缺码：被拒时的消息显示成裸码。两边都不报错。",
    ).toEqual({ onlyJava: [], onlyLoc: [] });
  });

  it("★★★ 中文两份必须逐字相同 —— 授权页与被拒消息说的是同一件事", () => {
    const zh = localeLabels("zh-CN");
    const drift = Object.keys(java)
      .filter((c) => zh[c] !== java[c])
      .map((c) => `${c}：后端「${java[c]}」 / 语言包「${zh[c]}」`)
      .sort();
    expect(
      drift,
      "同一个码，两处中文不一样 ——\n"
        + "  老板在授权页看到一句、员工被拒时看到另一句，\n"
        + "  而弄明白「到底缺什么」需要他们把两句凑在一起。\n"
        + "  改一处就要改两处（zh-CN 之外的语言包按各自语言译，不比对字面）。\n  "
        + drift.join("\n  "),
    ).toEqual([]);
  });
});
