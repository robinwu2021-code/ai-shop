// 设计 token 的自动化守卫（规范 §14：可自动化检查的硬指标）。
//
// 为什么需要这个：token 体系（五档圆角 / 语义色）不靠自觉维持。powerbank 的经验是
// 圆角明明收成了五档，组件层却一直混用 Tailwind 默认阶，五档形同虚设。
// 本工程从零起步，两条基线都是 **0**，不留额度 —— 一旦允许"暂时豁免"，豁免就会长期化。
import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname;

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.tsx?$/.test(name)) out.push(p);
  }
  return out;
}

/** 废弃的圆角类：Tailwind 默认阶，应改用 control/field/card/sheet/chip 五档。 */
const DEPRECATED_RADIUS = /\brounded-(sm|md|lg|xl|2xl|3xl|full)\b/g;

/** 豁免清单 —— 基线为空，**只允许保持为空**。要加豁免必须改下面那条断言，从而在 review 里显形。 */
const RADIUS_EXEMPT: string[] = [];

const rel = (f: string) => f.slice(ROOT.length).replace(/^\/+/, "");

describe("设计 token 守卫", () => {
  it("components/ 不使用废弃的圆角类", () => {
    const offenders: string[] = [];
    for (const file of walk(join(ROOT, "components"))) {
      const rel = file.slice(ROOT.length).replace(/^\/+/, "");
      if (RADIUS_EXEMPT.some((e) => rel.endsWith(e))) continue;
      const hits = readFileSync(file, "utf8").match(DEPRECATED_RADIUS);
      if (hits) offenders.push(`${rel}: ${[...new Set(hits)].join(", ")}`);
    }
    expect(offenders, `改用五档圆角（rounded-control/field/card/sheet/chip）：\n${offenders.join("\n")}`)
      .toEqual([]);
  });

  it("组件层不写死颜色字面量（hex / rgb / oklch）", () => {
    const offenders: string[] = [];
    for (const file of walk(join(ROOT, "components"))) {
      const rel = file.slice(ROOT.length).replace(/^\/+/, "");
      const src = readFileSync(file, "utf8");
      // 只看真正的颜色字面量，跳过注释行（注释里常引用 hex 说明来由）
      const hits = src
        .split("\n")
        .filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l))
        .join("\n")
        .match(/#[0-9a-fA-F]{6}\b|\brgb\(|\boklch\(/g);
      if (hits) offenders.push(`${rel}: ${[...new Set(hits)].join(", ")}`);
    }
    expect(offenders, `颜色一律走 token：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("豁免清单为空（要加豁免必须改这条断言，在 review 里显形）", () => {
    expect(RADIUS_EXEMPT).toEqual([]);
  });

  it("组件层不写字面控件高度 —— 一律走 --ctl-h / --row-h（否则密度切换是假的）", () => {
    // 判据只看 h-<数字>（Tailwind 的 rem 阶）。h-[var(--ctl-h)]、h-full、h-screen 不在其列。
    // 这条基线是 0：此前 Button 写 h-9/h-8/h-10、表头写 h-11，而输入框走了 token，
    // dense 模式下工具栏一行里控件高度**参差不齐** —— token 定义了没人消费，等于没有密度。
    const LITERAL_H = /\bh-(?:7|8|9|10|11|12)\b/g;
    /**
     * 豁免：**本体尺寸**而非控件高度档。
     * - switch / form-drawer 的开关轨道：5×9 的胶囊是这个控件的形状，跟着密度缩放会变形
     * - layout/：外壳高度（顶栏 56px、Rail 项）自成一档，不属于控件密度；要收敛的话
     *   应该进 lib/nav.ts 的布局常量，而不是塞进 --ctl-h
     */
    const H_EXEMPT = ["components/ui/switch.tsx", "components/ui/form-drawer.tsx", "components/layout/"];
    const offenders: string[] = [];
    for (const file of walk(join(ROOT, "components"))) {
      const rel = file.slice(ROOT.length).replace(/^\/+/, "");
      if (H_EXEMPT.some((e) => rel.includes(e))) continue;
      const src = readFileSync(file, "utf8")
        .split("\n")
        .filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l)) // 注释里会引用旧值说明来由
        .join("\n");
      const hits = src.match(LITERAL_H);
      if (hits) offenders.push(`${rel}: ${[...new Set(hits)].join(", ")}`);
    }
    expect(offenders, `控件高度走 h-[var(--ctl-h)]、行高走 h-[var(--row-h)]：\n${offenders.join("\n")}`)
      .toEqual([]);
  });
});

describe("页面层同样受约束（基线 0，不留额度）", () => {
  const pageFiles = () =>
    walk(join(ROOT, "app")).filter((f) => f.endsWith("page.tsx") && !f.includes("/dev/ui/"));

  const countAll = (re: RegExp) =>
    pageFiles().reduce((n, f) => n + (readFileSync(f, "utf8").match(re)?.length ?? 0), 0);

  it("页面不手写「仅可查看」—— 用 <ReadOnlyNotice>，句式与权限码才会统一", () => {
    expect(countAll(/仅可查看/g)).toBe(0);
  });

  it("页面不使用废弃圆角类", () => {
    const offenders: string[] = [];
    for (const file of pageFiles()) {
      // 用 match 而非 test：DEPRECATED_RADIUS 带 /g/，test() 会推进 lastIndex 而静默漏检
      const hits = readFileSync(file, "utf8").match(DEPRECATED_RADIUS);
      if (hits) offenders.push(`${file.slice(ROOT.length).replace(/^\/+/, "")}: ${[...new Set(hits)].join(", ")}`);
    }
    expect(offenders, `改用五档圆角：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("金额一律走 money()，不手写货币符号（RTL 下符号位置是反的，手写必错）", () => {
    expect(countAll(/`¥\$\{|"¥" *\+|`CNY \$\{/g)).toBe(0);
  });

  it("空态文案要说清为什么空，不许「暂无数据」", () => {
    expect(countAll(/empty="暂无(数据|记录)"/g)).toBe(0);
  });

  it("表单更新不许用非函数式 setState —— 连点会丢更新（已确诊的 bug）", () => {
    // 判据：`setXxx({ ...editing` / `setXxx({ ...form` 这类展开旧值的写法。
    // 它在输入框上不易触发（每次按键都重渲染），在复选框/开关连点时**必然**丢更新：
    // 第二次点击读到的是上一次渲染的闭包值。growth 页实机踩到过，当时同样的写法
    // 还散在另外 4 个页面 13 处 —— 现在统一走 lib/use-editable-config.ts 的 patch/set。
    const offenders: string[] = [];
    for (const f of pageFiles()) {
      const lines = readFileSync(f, "utf8").split("\n");
      lines.forEach((l, i) => {
        // 跳过注释：注释里写反面例子（"不要 setForm({...editing})"）是**应该的**
        if (/^\s*(\/\/|\*|\/\*)/.test(l)) return;
        if (/set\w+\(\s*\{\s*\.\.\.(editing|form)\b/.test(l)) {
          offenders.push(`${f.slice(ROOT.length).replace(/^\/+/, "")}:${i + 1}`);
        }
      });
    }
    expect(offenders, `改用 useEditableConfig 的 patch/set（函数式更新）：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("JSX 文案里不许写 markdown 星号 —— 页面不渲染 markdown，会原样显示成 **文字**", () => {
    // 只看 JSX 文本，跳过注释（注释里用 ** 强调是本仓的书写习惯，且不会被渲染）。
    // 实测踩过两次：Notice 里写「只列**已签收**批次」，界面上就是带星号的。
    const offenders: string[] = [];
    for (const f of pageFiles()) {
      const lines = readFileSync(f, "utf8").split("\n");
      lines.forEach((l, i) => {
        const isComment = /^\s*(\/\/|\*|\/\*)/.test(l) || l.includes("{/*");
        if (!isComment && /\*\*[^*]+\*\*/.test(l)) {
          offenders.push(`${f.slice(ROOT.length).replace(/^\/+/, "")}:${i + 1}`);
        }
      });
    }
    expect(offenders, `改用「」或去掉星号：\n${offenders.join("\n")}`).toEqual([]);
  });

  // ── 组合件的护栏：这几段样板每复制一次，就多一处"长得不一样"的地方 ──────────

  it("tab 与 URL 的同步只能走 usePageTab —— 手写的那 8 行漏掉 setPage(1) 就会翻页翻到空白", () => {
    const offenders = pageFiles().filter((f) => readFileSync(f, "utf8").includes('sp.get("tab")'));
    expect(offenders.map(rel), `改用 usePageTab(TABS, () => {...})：\n${offenders.map(rel).join("\n")}`).toEqual([]);
  });

  it("「上次修改」页脚只能由 ConfigCard 渲染 —— 各写各的措辞会让运营找不到同一行字", () => {
    const offenders: string[] = [];
    for (const f of pageFiles()) {
      readFileSync(f, "utf8").split("\n").forEach((l, i) => {
        if (l.includes("上次修改：")) offenders.push(`${rel(f)}:${i + 1}`);
      });
    }
    expect(offenders, `改用 <ConfigCard updatedAt= updatedBy=>：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("KPI 栅格与两列详情栅格只能用 StatRow / FieldGrid —— 断点各写各的会在窄屏错位", () => {
    const offenders: string[] = [];
    for (const f of pageFiles()) {
      readFileSync(f, "utf8").split("\n").forEach((l, i) => {
        if (/grid-cols-2 gap-4 lg:grid-cols-3|grid grid-cols-2 gap-x-4/.test(l)) {
          offenders.push(`${rel(f)}:${i + 1}`);
        }
      });
    }
    expect(offenders, `改用 <StatRow> / <FieldGrid>：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("列表页的分页状态只能走 usePaging —— 换了每页条数还停在第 5 页会落到不存在的页", () => {
    const offenders = pageFiles().filter((f) =>
      readFileSync(f, "utf8").includes("const [page, setPage] = useState("),
    );
    expect(offenders.map(rel), `改用 usePaging()：\n${offenders.map(rel).join("\n")}`).toEqual([]);
  });

  it("<Pagination> 必须给 onSize —— 少了它就只剩固定每页条数，对账场景要翻 5 页去数 100 条", () => {
    const offenders: string[] = [];
    for (const f of pageFiles()) {
      const src = readFileSync(f, "utf8");
      // <Pagination ... /> 逐个取出，检查是否带 onSize
      for (const m of src.matchAll(/<Pagination[\s\S]*?\/>/g)) {
        if (!m[0].includes("onSize=")) {
          offenders.push(`${rel(f)}:${src.slice(0, m.index).split("\n").length}`);
        }
      }
    }
    expect(offenders, `补上 onSize={setSize}：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("放进 <Toolbar> 的筛选控件必须声明 toChip —— 否则它的选中态不会出现在筛选回显里", () => {
    // 用户以为没筛，然后对着少掉的数据找半天。这是"加控件时顺手漏掉"的典型。
    const declared = new Set<string>();
    for (const f of [
      join(ROOT, "components/ui/filter-select.tsx"),
      join(ROOT, "components/archive.tsx"),
    ]) {
      for (const m of readFileSync(f, "utf8").matchAll(/^(\w+)\.toChip\s*=/gm)) declared.add(m[1]);
    }

    const offenders: string[] = [];
    for (const f of pageFiles()) {
      const src = readFileSync(f, "utf8");
      for (const tb of src.matchAll(/<Toolbar\b[\s\S]*?<\/Toolbar>/g)) {
        const inner = tb[0].slice(tb[0].indexOf(">") + 1);
        for (const m of inner.matchAll(/^\s*<([A-Z]\w*)/gm)) {
          if (!declared.has(m[1])) offenders.push(`${rel(f)}：<${m[1]}>`);
        }
      }
    }
    expect([...new Set(offenders)], `给它加 toChip（见 components/ui/filter-chip.ts）：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("components/ 下的每个文件都要在 README 的清单里 —— 清单漏了，新人就找不到已有件而重复造", () => {
    const readme = readFileSync(join(ROOT, "components/README.md"), "utf8");
    const files: string[] = [];
    for (const f of walk(join(ROOT, "components"))) {
      if (!/\.tsx?$/.test(f)) continue;
      if (/\.test\.tsx?$/.test(f)) continue;
      files.push(f.slice(join(ROOT, "components").length).replace(/^\/+/, ""));
    }
    // lib/ 下的页面级 hook 同理（README 有专门一节）
    for (const f of walk(join(ROOT, "lib"))) {
      const base = f.split("/").pop()!;
      if (/^use-.*\.ts$/.test(base) && !base.endsWith(".test.ts")) files.push(base);
    }
    const missing = files.filter((f) => !readme.includes(f) && !readme.includes(f.split("/").pop()!));
    expect(missing, `在 components/README.md 里补上：\n${missing.join("\n")}`).toEqual([]);
  });

  it("ui/ 不许反向依赖上层或业务模块 —— 一破，ui/ 就不再可复用", () => {
    const offenders: string[] = [];
    for (const f of walk(join(ROOT, "components/ui"))) {
      if (!/\.tsx?$/.test(f)) continue;
      const src = readFileSync(f, "utf8");
      for (const m of src.matchAll(/from "(@\/components\/(?!ui\/)[^"]+|@\/lib\/(types|permissions|phase|auth|nav)[^"]*)"/g)) {
        offenders.push(`${rel(f)} → ${m[1]}`);
      }
    }
    expect(offenders, `把业务语义留在 components/ 根：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("18 个业务页共用同一个页头件（TabHeader）—— PageTitle 只留给没有 L3 导航的工作台", () => {
    const offenders = pageFiles()
      .filter((f) => !/app\/(page|login)\.tsx$/.test(f) && !f.includes("/dev/"))
      .filter((f) => {
        const src = readFileSync(f, "utf8");
        return src.includes("<PageTitle") && !src.includes("<TabHeader");
      });
    expect(offenders.map(rel), `改用 <TabHeader>（单 tab 也走它，传 desc）：\n${offenders.map(rel).join("\n")}`).toEqual([]);
  });

  it("空态文案要写清「为什么空 / 下一步做什么」，不许只有一句话", () => {
    // 判据用长度是粗糙的，但"暂无数据"这类一句话空态确实全都很短，
    // 而写清了原因与出路的那些一律超过 20 字。踩过的坑：运营看到空表就以为系统坏了。
    const offenders: string[] = [];
    for (const f of pageFiles()) {
      const src = readFileSync(f, "utf8");
      for (const m of src.matchAll(/empty=\{?"([^"]+)"/g)) {
        if (m[1].length < 20) offenders.push(`${rel(f)}: empty="${m[1]}"`);
      }
    }
    expect(offenders, `补上「为什么空、下一步做什么」：\n${offenders.join("\n")}`).toEqual([]);
  });
});
