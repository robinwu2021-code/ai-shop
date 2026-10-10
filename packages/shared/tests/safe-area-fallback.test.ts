/**
 * `env(safe-area-inset-*)` **必须自带回退值**：`env(safe-area-inset-bottom, 0px)`。
 *
 * <p>来历（2026-09-05 真机）：小程序里购物车的结算条不贴底了，跟在商品卡后面
 * 浮在半空，而它上面的占位块一起塌成 0。iPhone 模拟器上一切正常。
 *
 * <p>⚠️ **第一版修错了方向**，值得记下来：当时以为「`env()` 不被支持 → 整条声明失效 →
 * 前面再写一条不含 `env()` 的就能兜住」。改完发上去，真机上一模一样。
 *
 * <p>真因在规范里：`env()` 引用一个**未定义**的环境变量、**且没有给回退值**时，
 * 该声明是 *invalid at computed-value time* —— 它在**解析期是有效的**（所以照样覆盖
 * 前面那条兜底），到计算期才失效，然后取**初始值**（`bottom` 的初始值是 `auto`），
 * 而**不会**回退到前一条声明。于是 `position: fixed` 的条 `bottom: auto`，
 * 停在静态位置；占位块的 `height` 同理变 `auto`，塌成 0。
 *
 * <p>真机实测钉死了这一点（vConsole → WXML → Details）：
 * `.ab` 的盒子是 `left 14 / right 13.67 / width 357.33`，与 `inset-inline: 28rpx`
 * 对着视口算完全吻合 —— **`fixed` 是生效的**，只有 `bottom` 变成了 `auto`（离底 469.81px）。
 *
 * <p>所以修法只有一个：**给 `env()` 加回退值**。`var()` 同理 ——
 * 未定义的自定义属性没有回退时也是 invalid at computed-value time。
 *
 * <p><b>内联样式里一律不许出现</b>：`:style` 是一次性求值的字符串，
 * 没有「后一条覆盖前一条」，失效就是彻底失效（`sh-actionbar` 的占位块高度
 * 当时就是这么塌的）。要安全区就单独挂一个属性，让它自己降级。
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const DIRS = ["packages/ui/src", "c-app/src", "b-app/src"];

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith(".vue") || p.endsWith(".css")) out.push(p);
  }
  return out;
}

const FILES = DIRS.flatMap((d) => walk(join(ROOT, d)));
const SAFE_AREA = /env\(safe-area-inset-[a-z]+\)/;
/** 一条 CSS 声明：`prop: value;`（简写与长写都算） */
const DECL = /^\s*([a-z-]+)\s*:\s*(.+);\s*$/;

describe("安全区兜底", () => {
  it("有文件可扫（否则下面全是空转）", () => {
    expect(FILES.length).toBeGreaterThan(50);
  });

  it("★★★ 每个 env/constant(safe-area) 都要带回退值 —— 没有回退就是 auto，不是「退回上一条」", () => {
    const bad: string[] = [];
    for (const f of FILES) {
      const lines = readFileSync(f, "utf8").split("\n");
      for (const [i, line] of lines.entries()) {
        // 注释里提到它是可以的 —— 这条守卫自己的说明就在注释里
        const code = line.replace(/\/\/.*$/, "").replace(/^\s*\*.*$/, "");
        for (const m of code.matchAll(/(env|constant)\(\s*safe-area-inset-[a-z]+\s*([,)])/g)) {
          if (m[2] !== ",") bad.push(`${relative(ROOT, f)}:${i + 1}  ${line.trim()}`);
        }
      }
    }
    expect(
      bad,
      "这些 env()/constant() 没有回退值 —— 环境变量未定义时该声明在**计算期**失效，"
        + "属性取初始值（bottom → auto、height → auto），而不是退回上一条声明。"
        + "写成 `env(safe-area-inset-bottom, 0px)`：\n  "
        + bad.join("\n  "),
    ).toEqual([]);
  });

  it("★★★ 内联样式（<script> 里）不许出现 env(safe-area) —— 它没法降级", () => {
    const bad: string[] = [];
    for (const f of FILES.filter((p) => p.endsWith(".vue"))) {
      const src = readFileSync(f, "utf8");
      const s = src.indexOf("<script");
      const e = src.indexOf("</script>");
      if (s < 0 || e < 0) continue;
      const script = src.slice(s, e);
      for (const [i, line] of script.split("\n").entries()) {
        // 注释里提它是可以的 —— 这条守卫自己的说明就在注释里
        const code = line.replace(/\/\/.*$/, "").replace(/^\s*\*.*$/, "");
        if (SAFE_AREA.test(code)) {
          bad.push(`${relative(ROOT, f)} 的 script 第 ${i + 1} 行：${line.trim()}`);
        }
      }
    }
    expect(
      bad,
      "内联样式是一次性求值的字符串，env() 失效就是整条失效（没有覆盖可言）。"
        + "把安全区拆成单独一个属性放进 CSS：\n  " + bad.join("\n  "),
    ).toEqual([]);
  });
});
