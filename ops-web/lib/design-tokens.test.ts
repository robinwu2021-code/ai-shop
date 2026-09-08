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

/**
 * 注释所在的行号集合（0 基）。
 *
 * <p>此前是**逐行**判断：`/^\s*(\/\/|\*|\/\*)/` 或 `l.includes("{/*")` ——
 * 只认得块注释的**第一行**。多行 `{/* … *\/}` 里从第二行起既不以 `//` 开头、
 * 也不含 `{/*`，于是被当成 JSX 文案扫了进来。
 *
 * <p>后果是这道闸报的是**假阳性**，而它的理由写着「页面不渲染 markdown，
 * 会原样显示」—— 注释根本不会被渲染，压根不在射程内。假阳性比漏报更伤：
 * 下一个人会去改注释的措辞来讨好闸门，而闸门本身错着。
 */
function commentLines(src: string): Set<number> {
  const out = new Set<number>();
  let inBlock = false;
  src.split("\n").forEach((l, i) => {
    if (inBlock) {
      out.add(i);
      if (l.includes("*/")) inBlock = false;
      return;
    }
    if (/^\s*\/\//.test(l)) { out.add(i); return; }
    // 行尾注释同样不渲染。漏了这一档的代价：`setIssued(r);  // **先摆出来** …`
    // 被当成 JSX 文案报了出来，而下一个人只能去改注释的措辞来讨好闸门。
    if (/\S\s*\/\/.*\*\*[^*]+\*\*/.test(l)) { out.add(i); return; }
    const start = l.indexOf("{/*") >= 0 ? l.indexOf("{/*") : l.indexOf("/*");
    if (start >= 0) {
      out.add(i);
      if (l.indexOf("*/", start) < 0) inBlock = true;
    }
  });
  return out;
}

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
  /**
   * ⚠️ **扫描面 = 结论的边界。**
   *
   * 这里此前是 `f.endsWith("page.tsx")` —— 而运营端的页面代码早就不住在 page.tsx 里了：
   * 24 个 `page.tsx` 共 9,259 行，`*-tab.tsx` / 抽屉 / 面板等 81 个文件共 15,556 行。
   * **63% 的页面代码一行都没被扫过**，于是这一整组断言常年全绿，而射程外积着：
   *
   *   · 29 个文件的既有违规（废弃圆角 · 手写分页 state · 非函数式 setState · 词典里的 markdown 星号）
   *   · 一个 `<Pagination>` 漏掉 `onSize`（storage-tab）—— 正是下面那条断言要拦的东西
   *   · 33 个 `<DataTable>` 调用点缺 loading/error/onRetry/empty
   *
   * 闸门全绿不等于规则被遵守；它只等于「被扫到的那部分没违规」。
   * 现在扫 `app/` 下**全部** .tsx（`/dev/` 是组件画廊与开发工具，不在其列）。
   */
  const pageFiles = () =>
    walk(join(ROOT, "app")).filter((f) => f.endsWith(".tsx") && !f.includes("/dev/"));

  /** 少数几条只对「路由入口」成立的规则（页头件）用它，别拿它当默认扫描面 */
  const entryPages = () =>
    walk(join(ROOT, "app")).filter((f) => f.endsWith("page.tsx") && !f.includes("/dev/"));

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
      const src = readFileSync(f, "utf8");
      const comments = commentLines(src);
      const lines = src.split("\n");
      lines.forEach((l, i) => {
        if (!comments.has(i) && /\*\*[^*]+\*\*/.test(l)) {
          offenders.push(`${f.slice(ROOT.length).replace(/^\/+/, "")}:${i + 1}`);
        }
      });
    }
    expect(offenders, `改用「」或去掉星号：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("★★ i18n 词典里同样不许写 markdown 星号 —— 它比 JSX 里更难发现", () => {
    /*
     * 上一条只扫 page.tsx，扫不到文案文件，于是同一个坑又踩了一次：
     * `login.forgotNote` 里写了星号，界面上原样显示成带星号的一行。
     * 而它比 JSX 里的更难发现 —— 文案离渲染点很远，写的时候看不到效果，
     * 只有真把那个抽屉点开才会看见。
     *
     * ⚠️ 第一版把 `copy.ts` 排除在外，理由写的是「那些文案过 <Notice> 的
     * markdown 渲染，星号在那里有效果」—— **那句话是错的**：
     * `Notice` 直接渲染 `{children}`，一个字符都不解析。
     * 实机截图里 `**失败的也记**` 原样出现，才发现这一点。
     * 现在两处一起扫：i18n 词典 + 各页 copy.ts。
     *
     * ⚠️ 2026-09-08：`finance/copy.ts`（14 行）与 `merchants/copy.ts`（8 行）
     * 曾以「并行会话正在改」为由挂在 PENDING 里。**豁免清单一旦有名字就会长期化** ——
     * 那两份的星号一直原样显示在界面上。现已一并清完（中文改「」，英文去掉星号），
     * PENDING 随之删除：这条从此没有例外。
     */
    const offenders: string[] = [];
    const files = [...walk(join(ROOT, "lib/i18n/messages")),
                   ...walk(join(ROOT, "app")).filter((f) => f.endsWith("copy.ts"))]
      .filter((f) => f.endsWith(".ts"));
    for (const f of files) {
      readFileSync(f, "utf8").split("\n").forEach((l, i) => {
        const isComment = /^\s*(\/\/|\*|\/\*)/.test(l);
        if (!isComment && /\*\*[^*]+\*\*/.test(l)) {
          offenders.push(`${f.slice(ROOT.length).replace(/^\/+/, "")}:${i + 1}`);
        }
      });
    }
    expect(offenders, `词典里的星号不会被渲染，改用「」或去掉：\n${offenders.join("\n")}`)
      .toEqual([]);
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

    /*
     * ⚠️ 两处此前是错的，扫描面一扩就露出来了：
     *
     * 1. `<Toolbar …/>` **自闭合**时，`<Toolbar[\s\S]*?</Toolbar>` 会一路吃到
     *    文件后面另一个 `</Toolbar>`，把中间的 `<DataTable>` `<Drawer>` 全算成
     *    工具栏子节点（credit-tab、rank-tab 就是这么被报出来的）。
     * 2. 工具栏里不止筛选器：新增/导出按钮、查询表单的输入框都不改变结果集，
     *    要求它们声明 toChip 是**假阳性**，而假阳性会训练人给按钮加没用的代码。
     *
     * 所以：先正确切出工具栏体，再只对**筛选控件家族**（名字里带 Select/Filter/
     * Toggle/Picker 的，以及已登记 toChip 的）要求登记。
     * 边界写清楚：`<Toolbar>` 里直接放的 `<Input>` 若真是实时筛选（改一个字就重查），
     * 它的状态同样进不了回显 —— 那一类由 review 认，这条正则认不出来。
     */
    const FILTERISH = /(Select|Filter|Toggle|Picker)$/;
    const offenders: string[] = [];
    for (const f of pageFiles()) {
      const src = readFileSync(f, "utf8");
      for (const m of src.matchAll(/<Toolbar\b/g)) {
        // 找开标签的结尾：`>` 要在花括号之外才算
        let depth = 0, end = -1, selfClosing = false;
        for (let i = m.index!; i < src.length; i++) {
          const ch = src[i];
          if (ch === "{") depth++;
          else if (ch === "}") depth--;
          else if (ch === ">" && depth === 0) { end = i; selfClosing = src[i - 1] === "/"; break; }
        }
        if (end < 0 || selfClosing) continue;      // 自闭合：没有子节点
        const close = src.indexOf("</Toolbar>", end);
        const inner = close < 0 ? "" : src.slice(end + 1, close);
        for (const t of inner.matchAll(/^\s*<([A-Z]\w*)/gm)) {
          if (FILTERISH.test(t[1]) && !declared.has(t[1])) offenders.push(`${rel(f)}：<${t[1]}>`);
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

  it("每个业务页都要有页头件，且只能是 TabHeader —— PageTitle 只留给没有 L3 导航的工作台", () => {
    /*
     * ⚠️ 这条此前判的是「用了 `PageTitle` 却没用 `TabHeader`」——
     * 于是**两个都没用**的那种反而穿过去了：`app/jobs/page.tsx` 直接从
     * `<HelpNote>` 起头，是 21 个业务域里唯一没有页头的一个，而它在 nav.ts 里
     * 明明有一条 L3 叶子。「有 A 没 B」与「A、B 都没有」是两个判据，只写前一个
     * 就会漏掉后一个 —— 而后一个恰恰是更严重的那种。
     */
    const wrong: string[] = [];
    const missing: string[] = [];
    // ⚠️ 排除项原先写的是 `app/(page|login).tsx` —— 而登录页的路径是
    // `app/login/page.tsx`，那条正则从来没排到它。此前没显形，只因为它也没用
    // `<PageTitle>`；判据一变严就露了出来。工作台（app/page.tsx）与登录页都没有
    // L3 导航，本来就不该有 TabHeader。
    const EXCLUDE = /app\/page\.tsx$|app\/login\/page\.tsx$/;
    for (const f of entryPages().filter((x) => !EXCLUDE.test(x))) {
      const src = readFileSync(f, "utf8");
      if (src.includes("<TabHeader")) continue;
      (src.includes("<PageTitle") ? wrong : missing).push(rel(f));
    }
    expect([...wrong, ...missing],
      `改用 <TabHeader>（单 tab 也走它，传 desc）：\n` +
      `  用了 PageTitle：${wrong.join("、") || "无"}\n` +
      `  一个页头件都没有：${missing.join("、") || "无"}`).toEqual([]);
  });

  it("空态文案要写清「为什么空 / 下一步做什么」，不许只有一句话", () => {
    /*
     * 判据用长度是粗糙的，但"暂无数据"这类一句话空态确实全都很短，
     * 而写清了原因与出路的那些一律超过 20 字。踩过的坑：运营看到空表就以为系统坏了。
     *
     * ⚠️ 这条此前只认 `empty="字面量"` —— 而 113 个 `empty=` 里有 95 个走的是
     * `empty={c.xxx}`（页面文案表在各页自己的 copy.ts 里）。也就是说这条规则**覆盖不到
     * 它 84% 的目标**，而它一直是绿的。把 copy 键解开之后一次查出 30 条短文案，
     * 短到 4 个字的（「暂无类目」）都在里面。
     *
     * 规则的覆盖面就是它的结论 —— 一条只看得见 16% 的断言，绿着也说明不了什么。
     */
    const zhMap = (dir: string): Record<string, string> => {
      const cp = join(dir, "copy.ts");
      let src: string;
      try { src = readFileSync(cp, "utf8"); } catch { return {}; }
      const i = src.indexOf("const zh = {");
      if (i < 0) return {};
      const body = src.slice(i, src.indexOf("\n};", i));
      const out: Record<string, string> = {};
      for (const m of body.matchAll(/^ {2}(\w+):\s*(.+?),?\s*$/gm)) {
        // 值可能是多段字符串相加；把所有字面量段拼起来
        const parts = [...m[2].matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((x) => x[1]);
        if (parts.length) out[m[1]] = parts.join("");
      }
      return out;
    };

    const offenders: string[] = [];
    for (const f of pageFiles()) {
      const src = readFileSync(f, "utf8");
      for (const m of src.matchAll(/empty=\{?"([^"]+)"/g)) {
        if (m[1].length < 20) offenders.push(`${rel(f)}: empty="${m[1]}"`);
      }
      const dict = zhMap(join(f, ".."));
      for (const m of src.matchAll(/empty=\{c\.(\w+)\}/g)) {
        const text = dict[m[1]];
        // 解不出来的不算违规（可能来自别处 import）—— 但也别假装查过了
        if (text !== undefined && text.length < 20) {
          offenders.push(`${rel(f)}: empty={c.${m[1]}} = "${text}"（${text.length} 字）`);
        }
      }
    }
    expect(offenders, `补上「为什么空、下一步做什么」：\n${offenders.join("\n")}`).toEqual([]);
  });

  // ── 静默失效：写了、但那个类根本不存在 ────────────────────────────────────
  //
  // 这一档比"违反规范"更难发现：没有报错、没有告警，浏览器只是**什么都不做**。
  // 一次盘点里查到 12 处，全部是人眼看不出来的：
  //
  //   · `txt-h3` ×2      —— 七档里没有这个类，两个小节标题一直按正文 14px/400 渲染
  //   · `txt-body-strong` —— 新员工初始密码那块 <code>，一个要照着念的字符串
  //   · `border-card-border` ×2 / `border-warning-line` / `text-destructive-text` ×2
  //   · `border-line` ×3 / `bg-surface` ×2 / `bg-surface-2` / `text-fg-2`
  //   · `text-danger` ×2 —— 结算超额与缺税号的两行警示，一直按正文色渲染
  //
  // Tailwind 4 里 `border-x` 只在 `--color-x` 注册进 @theme 时才生成，
  // `.txt-x` 只在 globals.css 里写了才存在。两者都拿产物对一遍就能判。

  const GLOBALS = readFileSync(join(ROOT, "app/globals.css"), "utf8");

  it("用到的 txt-* 字阶必须在 globals.css 里定义（不存在的类不会报错，只是不生效）", () => {
    const defined = new Set([...GLOBALS.matchAll(/\.(txt-[a-z0-9-]+)\b/g)].map((m) => m[1]));
    const offenders: string[] = [];
    for (const f of [...pageFiles(), ...walk(join(ROOT, "components"))]) {
      const src = readFileSync(f, "utf8");
      const comments = commentLines(src);
      src.split("\n").forEach((l, i) => {
        if (comments.has(i)) return;             // 注释里引用旧类名是说明来由，不是使用
        for (const m of l.matchAll(/\btxt-[a-z0-9-]+/g)) {
          if (!defined.has(m[0])) offenders.push(`${rel(f)}:${i + 1}  ${m[0]}`);
        }
      });
    }
    expect(offenders, `globals.css 里没有这些类：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("语义色 utility 必须对应 @theme 里注册过的 --color-*", () => {
    const theme = new Set([...GLOBALS.matchAll(/--color-([a-z0-9-]+)/g)].map((m) => m[1]));
    /** Tailwind 自带的调色板与关键字，不需要注册 */
    const BUILTIN = /^(white|black|transparent|current|inherit|red|green|blue|gray|slate|zinc|neutral|stone|amber|yellow|lime|emerald|teal|cyan|sky|indigo|violet|purple|fuchsia|pink|rose|orange)(-|$)/;
    /** 同前缀的**非颜色** utility（border-t / text-center / shadow-pop …）—— 不在射程内 */
    const NOT_A_COLOR = /^(t|b|l|r|s|e|x|y|0|2|4|8|center|left|right|start|end|justify|balance|pretty|wrap|nowrap|ellipsis|clip|none|solid|dashed|dotted|double|hidden|separate|collapse|pop|card|xs|sm|base|md|lg|xl|2xl|3xl|4xl|inner|offset|top|bottom|auto)(-|$)/;
    const offenders: string[] = [];
    for (const f of [...pageFiles(), ...walk(join(ROOT, "components"))]) {
      const src = readFileSync(f, "utf8");
      const comments = commentLines(src);
      src.split("\n").forEach((l, i) => {
        if (comments.has(i)) return;
        for (const m of l.matchAll(/\b(?:bg|text|border|ring|fill|stroke|divide|placeholder)-([a-z][a-z0-9-]*)(?![\w[])/g)) {
          const n = m[1];
          if (theme.has(n) || BUILTIN.test(n) || NOT_A_COLOR.test(n)) continue;
          offenders.push(`${rel(f)}:${i + 1}  ${m[0]}`);
        }
      });
    }
    expect(offenders, `@theme 里没有对应的 --color-*，这些类一个字节都不会生成：\n${offenders.join("\n")}`)
      .toEqual([]);
  });

  it("一个元素只挂一个字阶 —— 两个 txt-* 撞在一起，谁生效取决于样式表顺序", () => {
    // 实测有两处 `txt-strong text-lg`：两个类都设 font-size，都是单类选择器，
    // 胜负只由 globals.css 与 Tailwind utilities 的**先后**决定 —— 换个构建顺序就变。
    const offenders: string[] = [];
    for (const f of [...pageFiles(), ...walk(join(ROOT, "components"))]) {
      const src = readFileSync(f, "utf8");
      const comments = commentLines(src);
      src.split("\n").forEach((l, i) => {
        if (comments.has(i)) return;
        for (const m of l.matchAll(/className=(?:"([^"]*)"|\{`([^`]*)`\})/g)) {
          const cls = m[1] ?? m[2] ?? "";
          const tiers = [...cls.matchAll(/\btxt-(display|title|heading|body|strong|label|caption)\b/g)];
          if (tiers.length > 1) offenders.push(`${rel(f)}:${i + 1}  ${tiers.map((t) => t[0]).join(" + ")}`);
        }
      });
    }
    expect(offenders, `只留一个字阶：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("页面层不绕开字阶写字号（含裸 rounded：第六种圆角）", () => {
    /*
     * 字阶七档定义在 globals.css，而页面上曾有 175 处直接写 `text-xs` /
     * `text-[12px]` / `text-[13px]`，其中 11 处低到 10–11px ——
     * 规范里写着「字号下限 12px：此前页面上真实出现过 10px 文字，任何屏幕都读不清」，
     * 它就那么长回来了。定义了没人消费的档，与没有这个档是一回事。
     *
     * ⚠️ 结尾用 `(?![\w-])` 不能用 `\b`：`text-[12px]` 以 `]` 收尾，
     * 后面接空格时 `\b` 不成立 —— 第一版就是这么把带方括号的 66 处全漏掉的，
     * 还报了「已改 68 处」。
     *
     * 裸 `rounded`（4px）同理：五档圆角的那条正则要求带后缀，于是它一直是
     * 射程外的第六档，6 处（含 components/ 2 处）。
     */
    const offenders: string[] = [];
    for (const f of pageFiles()) {
      const src = readFileSync(f, "utf8");
      const comments = commentLines(src);
      src.split("\n").forEach((l, i) => {
        if (comments.has(i)) return;
        for (const m of l.matchAll(/\btext-(?:\[\d+px\]|xs|sm|base|lg|xl|2xl|3xl)(?![\w-])/g)) {
          offenders.push(`${rel(f)}:${i + 1}  ${m[0]} → 用 txt-display/title/heading/body/strong/label/caption`);
        }
        for (const m of l.matchAll(/className=(?:"([^"]*)"|\{`([^`]*)`\})/g)) {
          const cls = m[1] ?? m[2] ?? "";
          if (/(^|\s)rounded(\s|$)/.test(cls)) offenders.push(`${rel(f)}:${i + 1}  裸 rounded → 五档之一`);
        }
      });
    }
    expect(offenders, `字号与圆角都只走档：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("<DataTable> 调用点必须接上 error / onRetry —— 组件有错误态而调用点不传，等于没有", () => {
    /*
     * TDD-ops-组件库优化 §1.A 修的就是这个：接口挂掉时表格渲染成
     * 「没有符合条件的数据」，运营会去改筛选条件而不是报障。
     * 组件那一侧 2026-08-06 就加了 `error` 分支 —— 而一年后盘点，
     * 122 个调用点里 **33 处**没接（全部由 useQuery 支撑，15 处四项全缺）。
     * **修在库里没修到调用点，界面上就等于没修。**
     *
     * 分页列表请直接用 `PagedTable`：它从 `query` 里接出这几项，漏不掉。
     */
    const EXEMPT = [
      // 整块内容由外层 <ErrorState> 兜住（`if (error) return …`），行数据来自已到手的 data
      "app/communities/distribution-tab.tsx",
      "app/communities/health-tab.tsx",
      // 详情子表：行数据取自父查询已选中的那一行，自己没有查询
      "app/finance/pay-channel-tab.tsx",
    ];
    const offenders: string[] = [];
    for (const f of pageFiles()) {
      if (EXEMPT.some((e) => rel(f) === e)) continue;
      const src = readFileSync(f, "utf8");
      for (const m of src.matchAll(/<DataTable[\s\S]*?\/>/g)) {
        const miss = ["error", "onRetry"].filter((k) => !new RegExp(`\\b${k}=`).test(m[0]));
        if (miss.length) offenders.push(`${rel(f)}:${src.slice(0, m.index).split("\n").length}  缺 ${miss.join("/")}`);
      }
    }
    expect(offenders, `接上 error={q.error} onRetry={() => q.refetch()}，或改用 <PagedTable query={q}>：\n${offenders.join("\n")}`)
      .toEqual([]);
  });
});
