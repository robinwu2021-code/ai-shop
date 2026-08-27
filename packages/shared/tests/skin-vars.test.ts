// 端上引用的 `--sh-*` 必须真的在皮肤里定义过。
//
// 这条守卫的来历：商品编辑页里「从标准品填充」那行本该是灰色副文本，
// 实际渲染成和标题一样的深色。查下来是 `color: var(--sh-text-2)` ——
// 而皮肤定义的名字是 `--sh-sub`，`--sh-text-2` 从来不存在。
//
// **未定义的自定义属性不会报错**，它让整条声明在计算值阶段失效：
//   · `color` → 继承父级，于是灰字变深字
//   · `border-color` → 退回 currentColor
//   · `border-radius` → 直接被忽略（一张本该圆角的弹层就这么变成直角）
// 三者的共同点是「看起来只是样式没调好」，没有人会去怀疑变量名拼错了。
//
// 一次全仓扫描找出 11 处，分布在 3 个文件、5 个错名（text-1/2/3、border、accent、
// brand、warn），说明这不是某一次手滑，而是**皮肤改名后没有任何东西挡着**。
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
// 皮肤块由 `gen-skins.mjs` 生成后**写进 base.css**（不是单独的 skins.css）——
// 两端共用这一份，所以定义只有这一个来源
const SKIN_FILES = ["packages/ui/src/styles/base.css"];
const SCAN_DIRS = ["b-app/src", "c-app/src", "packages/ui/src"];
const EXT = /\.(vue|css)$/;

function walk(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (EXT.test(p)) out.push(p);
  }
  return out;
}

/** 皮肤里 `--sh-x:` 形式的定义 */
function defined(): Set<string> {
  const s = new Set<string>();
  for (const f of SKIN_FILES) {
    const src = readFileSync(join(ROOT, f), "utf8");
    for (const m of src.matchAll(/(--sh-[a-z0-9-]+)\s*:/g)) s.add(m[1]);
  }
  return s;
}

describe("皮肤变量", () => {
  it("★★★ 引用的 --sh-* 都定义过 —— 拼错不会报错，只会让整条声明静默失效", () => {
    const known = defined();
    expect(known.size, "没解析出任何皮肤变量，解析器和 skins.css 的写法分叉了").toBeGreaterThan(10);

    const bad: string[] = [];
    for (const dir of SCAN_DIRS) {
      for (const file of walk(join(ROOT, dir))) {
        // 先剥注释：好几处 `var(--sh-radius)` 是注释里**在讲这个坑本身**
        // （「此前写 var(--sh-radius) —— 该变量不存在」），扫进来就成了自我指控
        const src = readFileSync(file, "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
        // 只管**没有兜底值**的引用：`var(--x, 16rpx)` 拼错了也还有 16rpx 兜着，
        // 而 `var(--x)` 拼错就是整条声明作废
        for (const m of src.matchAll(/var\((--sh-[a-z0-9-]+)\s*\)/g)) {
          if (!known.has(m[1])) {
            const line = src.slice(0, m.index).split("\n").length;
            bad.push(`${relative(ROOT, file)}:${line}  var(${m[1]})`);
          }
        }
      }
    }
    expect(
      bad,
      `引用了皮肤里不存在的变量（整条声明会失效，但不报错）：\n${bad.join("\n")}\n` +
        `皮肤现有：${[...known].sort().join(" ")}`,
    ).toEqual([]);
  });

  // 上面那条**故意放行带兜底的写法**（`var(--x, 16rpx)` 拼错了也还有 16rpx 兜着）。
  // 2026-08-27 发现那正是它看不见的一类：`store-pick` 写的是
  // `color: var(--sh-warn, var(--sh-sub))` —— `--sh-warn` 不存在（正名 `--sh-warning`），
  // 于是那行本该是提醒的字**永远渲染成普通灰**。没有报错、没有失效、也没人发现，
  // 因为**兜底把拼错的后果盖住了**：它不是「整条声明作废」那种显眼的坏，
  // 是「一直是另一个值」这种不显眼的坏。
  //
  // 判据不是「不许用兜底」—— 兜底本身没问题（`var(--sh-line-soft, var(--sh-line))`
  // 这种「有就用、没有就退回」是正当的）。判据是：**兜底里的那个名字也得存在过**。
  // 一个从来没在任何皮肤里定义过的 `--sh-*`，就是拼错，不是「可选的」。
  it("★★★ 带兜底的引用，主变量也得存在 —— 兜底会把拼错的后果盖住", () => {
    // 这里的「已定义」要比上一条宽：密度变量（`--sh-pad-card` / `--sh-fs-sub` …）
    // 定义在各端的 `App.vue` 而不是皮肤块里，**库组件用兜底正是为了让没定义它的
    // 那一端退回默认值** —— 那是正当用法，不是拼错。第一次跑这条断言时它误报了
    // 这 7 处，判据比结论更容易错。
    const known = new Set(defined());
    for (const f of ["b-app/src/App.vue", "c-app/src/App.vue"]) {
      const src = readFileSync(join(ROOT, f), "utf8");
      for (const m of src.matchAll(/(--sh-[a-z0-9-]+)\s*:/g)) known.add(m[1]);
    }
    const bad: string[] = [];
    // ⚠️ 只扫 B 端与库：C 端还有一处（biz-goods-card 的 `--sh-radius-sm`），
    // 是 C 端那一轮的活。写死成两端会让这条从第一天起就是红的。
    for (const dir of ["b-app/src", "packages/ui/src"]) {
      for (const file of walk(join(ROOT, dir))) {
        if (!EXT.test(file)) continue;
        const src = readFileSync(file, "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
        for (const m of src.matchAll(/var\((--sh-[a-z0-9-]+)\s*,/g)) {
          if (!known.has(m[1])) {
            const line = src.slice(0, m.index).split("\n").length;
            bad.push(`${relative(ROOT, file)}:${line}  var(${m[1]}, …) —— 该变量从未定义，永远走兜底`);
          }
        }
      }
    }
    expect(
      bad,
      `兜底盖住了拼错的变量名（不会报错，只是一直用着另一个值）：\n${bad.join("\n")}`,
    ).toEqual([]);
  });
});
