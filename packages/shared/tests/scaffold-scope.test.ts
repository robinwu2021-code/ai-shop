import { describe, expect, it } from "vitest";
import { readFileSync, globSync } from "node:fs";

import { resolve } from "node:path";

/**
 * **页面模板里不许有东西挂在 `<sh-scaffold>` 外面。**
 *
 * 这套设计变量声明在 `:root, .sh-root` 上，而**小程序里没有 `:root`** ——
 * 根节点叫 `page`，那条选择器一个节点都不匹配。真正生效的只有 scaffold
 * 根节点上的那个 `.sh-root`，所以挂在 scaffold 外面的节点**一个变量都继承不到**。
 *
 * <p>它坏得很难认：`background: var(--sh-scrim)` 落空不是报错，是**透明**。
 * 手机号绑定弹层因此变成一堆没有底的文字，直接浮在商品列表上 ——
 * 现场看起来像「页面串行了」，没人会往「弹窗的遮罩没上色」上想。
 * 而 H5 完全正常，因为浏览器里 `:root` 是匹配的：
 * **本地怎么点都对，只有小程序里坏**（同一族的坑见 uni-app-runtime-breaks-h5-ok）。
 */
const ROOT = resolve(__dirname, "../../..");

function pageFiles(): string[] {
  return globSync("{c-app,b-app}/src/pages/**/*.vue", { cwd: ROOT }).sort();
}

/** 取 `</sh-scaffold>` 与 `</template>` 之间的内容，去掉注释与空白 */
function tailAfterScaffold(src: string): string {
  const m = /<\/sh-scaffold>([\s\S]*?)<\/template>/.exec(src);
  if (!m) return "";
  return m[1].replace(/<!--[\s\S]*?-->/g, "").trim();
}

describe("页面模板：scaffold 之外不许有节点", () => {
  it("★★ 所有页面的 sh-scaffold 后面只允许有注释和空白", () => {
    const offenders: string[] = [];
    for (const rel of pageFiles()) {
      const src = readFileSync(resolve(ROOT, rel), "utf-8");
      if (!src.includes("</sh-scaffold>")) continue; // 不用 scaffold 的页面不在此规则内
      const tail = tailAfterScaffold(src);
      if (tail) offenders.push(`${rel} → ${tail.replace(/\s+/g, " ").slice(0, 90)}`);
    }
    expect(
      offenders,
      "这些节点挂在 sh-scaffold 外面，拿不到任何 --sh-* 变量（小程序里没有 :root）。\n" +
        "把它们移到 </sh-scaffold> 之前即可 —— scaffold 的 transform 本来就是\n" +
        "为「页面内的 position: fixed 悬浮层」准备的包含块，移进去不会影响定位。\n" +
        offenders.join("\n"),
    ).toEqual([]);
  });
});
