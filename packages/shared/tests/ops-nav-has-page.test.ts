import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * `nav.ts` 里每个菜单叶子都要真的落到一个页面（以及它声明的那个 `?tab=`）。
 *
 * <h2>为什么 ops-endpoint-exists 拦不住这一型</h2>
 *
 * 那道闸查的是「ops-web 发出的调用，后端有没有对应端点」。
 * 而**菜单项没有页面时它不发任何调用** —— 没有调用就没有可查的东西，闸门照绿。
 * 它自己的注释里正警告「死按钮：页面看起来是活的，运营不会怀疑功能没做，
 * 只会觉得系统坏了」，但它够不着这一型。
 *
 * <h2>今天的基线是 0</h2>
 *
 * 2026-09-09 量过：124 个叶子全部落得到页面。所以这道闸是**预防性**的，
 * 起点就是绿的 —— 它挡的是「加了菜单项、页面下次再写」这个很自然的顺序。
 * 静态导出下点进去是 404，而运营看到的是一个存在的入口。
 */
const ROOT = join(import.meta.dirname, "../../..");
const NAV = join(ROOT, "ops-web/lib/nav.ts");
const APP = join(ROOT, "ops-web/app");

type Leaf = { href: string; label: string };

function leaves(): Leaf[] {
  const src = readFileSync(NAV, "utf8");
  return [...src.matchAll(/\{[^{}]*href:\s*"([^"]+)"[^{}]*label:\s*"([^"]+)"[^{}]*\}/g)]
    .map((m) => ({ href: m[1]!, label: m[2]! }));
}

/** 这个叶子落不到页面的原因；落得到则返回 null */
function unreachable(l: Leaf): string | null {
  const [path, query] = l.href.split("?");
  const dir = join(APP, path === "/" ? "" : path!);
  if (!existsSync(join(dir, "page.tsx"))) return `没有 ${path}/page.tsx`;
  const tab = query?.match(/tab=([^&]+)/)?.[1];
  if (!tab) return null;
  /*
   * tab 不要求有独立的 `<name>-tab.tsx` —— orders 那六个 tab 全在 page.tsx 里内联，
   * 那是合法写法。所以判据是「这个路由目录下有没有人认得这个 tab 字面量」。
   */
  const body = readdirSync(dir)
    .filter((f) => f.endsWith(".tsx") || f.endsWith(".ts"))
    .map((f) => readFileSync(join(dir, f), "utf8"))
    .join("\n");
  return body.includes(`"${tab}"`) ? null : `${path}/ 下没人认得 tab "${tab}"`;
}

describe("运营端菜单 · 每个叶子都落得到页面", () => {
  const all = leaves();

  it("扫描面下界 —— 至少 100 个叶子", () => {
    // 正则失配会让下面那条对着空集通过，而那看起来像「一个死按钮都没有」。
    expect(all.length, `只解析出 ${all.length} 个叶子`).toBeGreaterThanOrEqual(100);
  });

  it("★★★ 没有死按钮 —— 菜单里有、点进去 404，运营只会以为系统坏了", () => {
    const dead = all.map((l) => {
      const why = unreachable(l);
      return why ? `${l.label}（${l.href}）—— ${why}` : null;
    }).filter(Boolean);
    expect(
      dead,
      "这些菜单项落不到页面：\n  " + dead.join("\n  ") + "\n" +
        "三选一：补页面；把菜单项拿掉；或者标 soon: true（导航里灰显不可点）。\n" +
        "留着不管最坏 —— ops-endpoint-exists 看不见这一型（没有页面就没有调用）。",
    ).toEqual([]);
  });
});
