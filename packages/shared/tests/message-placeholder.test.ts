// 带占位符的错误文案，**每一处抛出都必须传参** —— 否则界面上显示的是 `{0}`。
//
// 这条守卫来自一次自己造的回归：把 70006 的文案从
//   「你的角色不能做这件事」
// 改成
//   「这一步需要「{0}」的权限」
// 之后，只更新了 `PermChecker` 的两处抛出点，**漏了第三处**
// （`MerchantRoleServiceImpl` 里「这个码不能授给自定义角色」那条）。
// 于是老板建角色时看到的是：
//   这一步需要「{0}」的权限，你在这家店的角色没有——让店主给你加个角色
//
// 两件事同时错了：占位符没替换，而且那句话本身也不对（他就是店主）。
// 前者是这条守卫管的：**改一句文案会让远处的调用点静默变错**，
// 而 Java 编译器对此一无所知 —— 参数在运行时才拼。
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const BACKEND = join(ROOT, "backend");
const ERROR_CODE = join(
  BACKEND,
  "shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java",
);
const MESSAGES = join(
  BACKEND,
  "shop-app/src/main/resources/i18n/messages.properties",
);

function javaFiles(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) javaFiles(p, out);
    else if (e.endsWith(".java") && !p.includes(`${"/"}test/`)) out.push(p);
  }
  return out;
}

/** 文案里带 `{0}` 的消息键 */
function keysWithPlaceholder(): Set<string> {
  const src = readFileSync(MESSAGES, "utf8");
  const out = new Set<string>();
  for (const line of src.split("\n")) {
    const i = line.indexOf("=");
    if (i > 0 && line.slice(i).includes("{0}")) out.add(line.slice(0, i).trim());
  }
  return out;
}

/** 枚举常量名 → 消息键 */
function codeToKey(): Map<string, string> {
  const src = readFileSync(ERROR_CODE, "utf8");
  const out = new Map<string, string>();
  for (const m of src.matchAll(/^\s{4}([A-Z][A-Z_0-9]*)\(\d+,\s*"([\w.]+)"\)/gm)) {
    out.set(m[1]!, m[2]!);
  }
  return out;
}

describe("带占位符的错误文案", () => {
  const placeholders = keysWithPlaceholder();
  const mapping = codeToKey();

  it("两个源都读得到（正则失效时不要静默通过）", () => {
    expect(mapping.size, "ErrorCode 里一个常量都没扫到").toBeGreaterThan(20);
    expect(placeholders.size, "messages.properties 里一条带 {0} 的都没扫到")
      .toBeGreaterThan(0);
  });

  it("★★★ 抛这些码时必须带参数 —— 不带的话用户看到的是字面的 `{0}`", () => {
    /** 带占位符的那些 ErrorCode 常量名 */
    const needArg = [...mapping.entries()]
      .filter(([, key]) => placeholders.has(key))
      .map(([code]) => code);

    const offenders: string[] = [];
    for (const f of javaFiles(BACKEND)) {
      const src = readFileSync(f, "utf8");
      for (const code of needArg) {
        /*
         * 只认「紧跟着就是 `)`」的调用 —— 那才是没传参的形状。
         * 跨行写法（`of(\n  ErrorCode.X, arg)`）也要能识别，所以允许空白。
         */
        const re = new RegExp(
          `BizException\\.of\\(\\s*(?:[\\w.]*ErrorCode\\.)?${code}\\s*\\)`,
          "g",
        );
        if (re.test(src)) {
          offenders.push(`${f.replace(BACKEND, "backend")} → ${code}`);
        }
      }
    }

    expect(
      offenders.sort(),
      "这些地方抛了带占位符的错误码却没传参 ——\n"
        + "  用户看到的是文案里字面的 `{0}`。改一句文案不会让编译器报错，\n"
        + "  参数是运行时才拼的，所以这类回归只能靠跑到那一屏才发现。\n"
        + "  修：补上参数，或者这里本来就该用另一个错误码（多半是后者 ——\n"
        + "  一个不需要说明「缺哪个权限」的场景，多半根本不是权限不足）。\n  "
        + offenders.join("\n  "),
    ).toEqual([]);
  });
});
