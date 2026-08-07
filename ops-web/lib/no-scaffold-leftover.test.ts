// 脚手架残留守卫。
//
// 组件库与工程地基提取自另一个项目（充电宝运营端），提取时**业务语义必须全部剥掉**。
// 这条测试是那次剥离的棘轮：残留一旦回流（复制粘贴老页面、抄一段老注释）立刻红。
//
// 判据只看**业务词**，不看技术词：`powerbank` 出现在"提取自 powerbank/ops-web"这类
// 溯源说明里是**应该保留的**（说清地基从哪来是诚实的），所以溯源文件在白名单里。
import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname;

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    if (name === "node_modules" || name === ".next" || name === "out") continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.(tsx?|css)$/.test(name)) out.push(p);
  }
  return out;
}

const files = () =>
  [...walk(join(ROOT, "app")), ...walk(join(ROOT, "components")), ...walk(join(ROOT, "lib"))]
    .filter((f) => !f.endsWith("no-scaffold-leftover.test.ts"));

const rel = (f: string) => f.slice(ROOT.length).replace(/^\/+/, "");

describe("脚手架业务残留", () => {
  /**
   * 上游项目的业务名词。出现即说明抄了它的业务，而不是只用了它的组件。
   * ⚠️ 「租户」**不在**这个清单里：它是本项目自己的概念（矩阵 P-17.1.6 预留 `tenant_no=MAIN`），
   * 第一版把它当成外来词，测试直接把 lib/types/common.ts 判红了 —— 判据要认业务，不是认字形。
   */
  const FOREIGN_TERMS = ["机柜", "充电宝", "仓位", "代理商", "场地方", "简电", "巡检", "工单派单"];

  /** 溯源说明白名单：这些文件里提到上游项目名是**有意的**，说明地基出处。 */
  const ORIGIN_NOTES = [
    "app/globals.css",
    "lib/nav.ts",
    "lib/design-tokens.test.ts",
    "lib/mock/db/helpers.ts",
  ];

  it("业务代码里不出现上游项目的业务名词", () => {
    const offenders: string[] = [];
    for (const f of files()) {
      const src = readFileSync(f, "utf8");
      const hit = FOREIGN_TERMS.filter((t) => src.includes(t));
      if (hit.length) offenders.push(`${rel(f)}: ${hit.join(", ")}`);
    }
    expect(offenders, `这些词属于上游项目的业务，不该出现在本项目：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("项目名只出现在溯源说明里，不出现在业务代码里", () => {
    const offenders: string[] = [];
    for (const f of files()) {
      if (ORIGIN_NOTES.some((w) => rel(f) === w)) continue;
      if (readFileSync(f, "utf8").includes("powerbank")) offenders.push(rel(f));
    }
    expect(offenders, `出处说明请集中在 ${ORIGIN_NOTES.join(" / ")}：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("代理端门户机制已删除且未回流（受限视角走数据域，不走第二套菜单）", () => {
    const offenders = files().filter((f) => /portalFor|portalTitleOverride|usePortalTitle/.test(readFileSync(f, "utf8")));
    expect(offenders.map(rel)).toEqual([]);
  });
});
