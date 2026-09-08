// 规范体检：在**已渲染的 DOM 上**跑扫描，而不是读源码。
//
// 为什么在 DOM 上跑：这页把 23 个组件的全部状态都渲染出来了，所以"页面上出现过的
// 违规"就等于"组件层的违规"。DOM 扫描的好处是**不会过期**——组件改好了，
// 这里的清单自动变短，不需要有人回来维护一份手抄的问题列表。
//
// 局限（诚实记在这里，别让读者高估它）：
// 1. 只看得到当前渲染出来的节点。抽屉/弹窗关着时其内容不在 DOM 里，需要打开后再点「重新体检」。
// 2. class 字符串扫描判断不了「这个 h-9 是不是刻意的」，所以结论是**线索**不是判决。
// 3. 不覆盖没在本页出现的组件（quick-actions、layout/*）。

export type Finding = {
  /** 归属组件（最近的 [data-comp] 祖先） */
  comp: string;
  /** 违反了哪条规范 */
  rule: string;
  /** 实测到的值 */
  detail: string;
  /** 定位线索：标签名 + 截断后的 class */
  sample: string;
};

export type AuditResult = {
  findings: Finding[];
  /** 扫过多少个元素 / 多少个可聚焦元素 */
  scanned: number;
  focusable: number;
};

const RULES = {
  radius: "圆角只允许 chip/field/card/sheet 四档",
  focus: "可聚焦元素必须有 focus-visible 环",
  focusOffset: "焦点环缺 ring-offset（globals.css 统一写法要求 offset-2 + offset-[--ring-offset-bg]）",
  z: "浮层层级必须走 z-[var(--z-…)]",
  hex: "颜色必须走 token，不许写死 hex",
  shadow: "阴影只有 shadow-card / shadow-pop 两档",
  dur: "过渡时长走 var(--dur) / var(--dur-fast)",
  ctlH: "控件高走 var(--ctl-h)、表格行高走 var(--row-h)（否则密度切换无效）",
  numAlign: "数字单元格应右对齐（规范 §12：数字右对齐 + 等宽）",
  pkWeight: "主键列应加强字重（规范 §12：扫描时需要锚点）",
} as const;

function classOf(el: Element): string {
  return el.getAttribute("class") ?? "";
}

/**
 * 归属：最近的 `[data-comp]` 祖先。
 *
 * `fallback` 是给**真实业务页**用的：那里没有 `data-comp` 标记（只有组件画廊有），
 * 不给回落值的话每一条都归到「(未归属)」，归并之后是一坨看不出该开哪一页的清单 ——
 * 一份指不到位置的问题清单当不了工作单。
 */
function compOf(el: Element, fallback = "(未归属)"): string {
  const owner = el.closest("[data-comp]");
  return owner?.getAttribute("data-comp") ?? fallback;
}

function sampleOf(el: Element): string {
  const cls = classOf(el).replace(/\s+/g, " ").trim();
  return `<${el.tagName.toLowerCase()}> ${cls.length > 90 ? cls.slice(0, 90) + "…" : cls}`;
}

const FOCUSABLE = 'button,a[href],input,select,textarea,[tabindex]:not([tabindex="-1"])';

/**
 * 允许的圆角档位（px 数值）。rounded-full 在 Tailwind 4 下会算成极大值，单独按"药丸"处理。
 *
 * ⚠️ 从**被扫的那个文档**读，不是永远读当前页：跨 iframe 扫真实页面时
 * （见 `app/dev/pages`），两个文档虽然共用同一份 globals.css，但取值一旦分家
 * （比如以后按端做主题差异），读错文档就会把合法圆角全判成违规 —— 而那种假阳性
 * 会让整份清单失去可信度。
 */
function allowedRadii(doc: Document): number[] {
  const cs = doc.defaultView!.getComputedStyle(doc.documentElement);
  const read = (name: string) => parseFloat(cs.getPropertyValue(name)) || NaN;
  // --r-control 是刻意只给 16px 级小控件（Checkbox）开的第五档，见 globals.css 里的注释：
  // 四档最小的 field(11px) 会把 16px 方块夹成正圆、与单选点撞脸。漏掉它会把 Checkbox
  // 唯一正确的圆角误判成"违规"。
  return [0, read("--r-control"), read("--r-field"), read("--r-card"), read("--r-sheet")].filter((x) => !Number.isNaN(x));
}

/** 四个角是否同一个值；返回该值（px），不一致返回 null（不一致本身不判违规，四角不同是合法设计） */
function uniformRadius(el: Element): number | null {
  const cs = el.ownerDocument.defaultView!.getComputedStyle(el);
  const corners = [cs.borderTopLeftRadius, cs.borderTopRightRadius, cs.borderBottomRightRadius, cs.borderBottomLeftRadius];
  // 只处理简单 <n>px 形态；百分比/椭圆(两值)一律跳过
  const vals = corners.map((c) => (/^-?[\d.]+px$/.test(c.trim()) ? parseFloat(c) : NaN));
  if (vals.some(Number.isNaN)) return null;
  return vals.every((v) => Math.abs(v - vals[0]) < 0.51) ? vals[0] : null;
}

/**
 * 要扫哪些节点。
 *
 * - `specimens`（组件画廊 `/dev/ui` 用）：只看 `[data-specimen]` 容器内的节点，
 *   也就是**真正的组件实例**。那一页自己的骨架（分区标题、状态标签、体检表本身）
 *   不参与扫描，否则清单里一半是那页自己的 div，读的人没法当工作单用。
 * - `subtree`（真实业务页 `/dev/pages` 用）：整棵子树。业务页上没有
 *   `data-specimen` 标记，按前一种口径扫出来的结果**恒为空** ——
 *   而空集看起来和「全都合规」一模一样。
 */
export type AuditScope = "specimens" | "subtree";

function targets(root: HTMLElement, scope: AuditScope): HTMLElement[] {
  if (scope === "subtree") return Array.from(root.querySelectorAll<HTMLElement>("*"));
  const out: HTMLElement[] = [];
  for (const box of Array.from(root.querySelectorAll<HTMLElement>("[data-specimen]"))) {
    out.push(...Array.from(box.querySelectorAll<HTMLElement>("*")));
  }
  return out;
}

/**
 * 表格级检查：数字列右对齐 + 主键列字重。
 *
 * **这两条只能在 DOM 上查，源码 grep 查不了** —— 判断"这一列是不是数字列"
 * 需要看渲染出来的内容，判断"对齐/字重对不对"需要看计算样式。
 * 所以它们不在 lib/design-tokens.test.ts（那是源码扫描），而在这里。
 */
/**
 * 单元格里**真正承载文字的那个节点**。
 *
 * ⚠️ 此前这两条表格规则直接量 `<td>` 的 computed style —— 而单元格内容多半包一层
 * span（`IdCell` 就是 `<td><span class="txt-caption">…</span></td>`）。
 * td 本身继承 14px/400，span 是 12px/500，量 td 得到的是**外壳的样式，不是看得见的那个**。
 * 实测：7 个长单号列改用 `IdCell` 之后，界面上确实变成了 500，而这条规则照旧报违规。
 *
 * 判据：只要还有唯一一个元素子节点、且它包住了全部文字，就往下走。
 */
function textHost(cell: Element): Element {
  let el = cell;
  for (let i = 0; i < 4; i++) {
    const kids = Array.from(el.children);
    if (kids.length !== 1) break;
    const kid = kids[0];
    if ((kid.textContent ?? "").trim() !== (el.textContent ?? "").trim()) break;
    el = kid;
  }
  return el;
}

function auditTables(root: HTMLElement, findings: Finding[], label?: string) {
  for (const table of Array.from(root.querySelectorAll("table"))) {
    const rows = Array.from(table.querySelectorAll<HTMLTableRowElement>("tbody tr"));
    if (rows.length < 2) continue;
    const colCount = rows[0].cells.length;

    for (let c = 0; c < colCount; c++) {
      const cells = rows.map((r) => r.cells[c]).filter(Boolean);
      if (cells.length < 2) continue;
      const texts = cells.map((td) => (td.textContent ?? "").trim()).filter(Boolean);
      if (texts.length < 2) continue;

      /*
       * 纯数字列：只含数字/千分位/小数点/货币符号/斜杠（如 "7/8"）与空白。
       *
       * ⚠️ 两条例外，都是实测踩出来的假阳性：
       * 1. **整列都是占位符**（`-` / `—` / `/`）。归档时间列在没有归档记录时
       *    整列都是 `-`，而 `-` 落在上面那个字符集里 —— 于是一个日期列被判成数字列。
       *    要求至少有一个真带数字的值。
       * 2. **调用点显式声明过 `align`**（`data-col-align`）。「区划码 11」长得像数字，
       *    按规范却该左对齐 —— 它是标识符不是数量。写了 align 就是做过判断，
       *    工具不该再替他下结论；不然下一个人会去给编号列加 numeric。
       */
      const hasDigit = texts.some((t) => /\d/.test(t));
      const declared = cells.some((td) => td.hasAttribute("data-col-align"));
      const numeric = hasDigit && !declared
        && texts.every((t) => /^[\d.,\s/%+-]+$|^[A-Z]{3}\s[\d.,]+$/.test(t));
      if (numeric) {
        const cell0 = cells[0];
        const align = cell0.ownerDocument.defaultView!.getComputedStyle(cell0).textAlign;
        if (align !== "right" && align !== "end") {
          findings.push({
            comp: compOf(table, label), rule: RULES.numAlign,
            detail: `第 ${c + 1} 列 text-align: ${align}（示例 "${texts[0]}"）`,
            sample: sampleOf(cells[0]),
          });
        }
      }

      // 主键列：第一个非选择框列，内容形如 CAB1000 / ORD500001（字母前缀 + 数字）
      const isPk = c <= 2 && texts.every((t) => /^[A-Z]{2,4}[-_]?\d{3,}$/.test(t));
      if (isPk) {
        // 量承载文字的那个节点，不是 td 外壳
        const host = textHost(cells[0]);
        const w = Number(host.ownerDocument.defaultView!.getComputedStyle(host).fontWeight);
        if (w < 500) {
          findings.push({
            comp: compOf(table, label), rule: RULES.pkWeight,
            detail: `第 ${c + 1} 列 font-weight: ${w}（示例 "${texts[0]}"）`,
            sample: sampleOf(cells[0]),
          });
        }
      }
    }
  }
}

export function audit(root: HTMLElement, scope: AuditScope = "specimens", label?: string): AuditResult {
  const findings: Finding[] = [];
  const allowed = allowedRadii(root.ownerDocument);
  const pill = 100; // ≥100px 视为药丸（= --r-chip 的 9999px）
  const all = targets(root, scope);

  for (const el of all) {
    const cls = classOf(el);
    if (el.hasAttribute("data-audit-skip")) continue;

    // ── 圆角
    const r = uniformRadius(el);
    if (r != null && r > 0.51 && r < pill && !allowed.some((a) => Math.abs(a - r) < 0.51)) {
      findings.push({ comp: compOf(el, label), rule: RULES.radius, detail: `border-radius: ${r}px`, sample: sampleOf(el) });
    }

    // ── 硬编码颜色（class 里的 hex 与 inline style 里的 hex）
    const styleAttr = el.getAttribute("style") ?? "";
    const hex = [...cls.matchAll(/#[0-9a-fA-F]{3,8}\b/g), ...styleAttr.matchAll(/#[0-9a-fA-F]{3,8}\b/g)];
    if (hex.length) {
      findings.push({ comp: compOf(el, label), rule: RULES.hex, detail: hex.map((m) => m[0]).join(" "), sample: sampleOf(el) });
    }

    // ── 硬编码 z-index（放过 z-[var(--z-…)] 与 z-0/z-10 这类语义无关的层内排序？——
    //    不放过：z-40/50 以上一定是浮层，正是踩过坑的地方）
    const z = cls.match(/(?:^|\s)z-\[?(\d+)\]?(?:\s|$)/);
    if (z && Number(z[1]) >= 20) {
      findings.push({ comp: compOf(el, label), rule: RULES.z, detail: z[0].trim(), sample: sampleOf(el) });
    }

    // ── 阴影
    const sh = cls.match(/(?:^|\s)shadow-(sm|md|lg|xl|2xl|inner)(?:\s|$)/);
    if (sh) {
      findings.push({ comp: compOf(el, label), rule: RULES.shadow, detail: sh[0].trim(), sample: sampleOf(el) });
    }

    // ── 过渡时长
    const du = cls.match(/(?:^|\s)duration-(\d+)(?:\s|$)/);
    if (du) {
      findings.push({ comp: compOf(el, label), rule: RULES.dur, detail: du[0].trim(), sample: sampleOf(el) });
    }

    // ── 控件高度 / 行高硬写
    const isCtl = /^(button|input|select|textarea)$/.test(el.tagName.toLowerCase());
    const h = cls.match(/(?:^|\s)h-(8|9|10|11)(?:\s|$)/);
    if (isCtl && h) {
      findings.push({ comp: compOf(el, label), rule: RULES.ctlH, detail: h[0].trim(), sample: sampleOf(el) });
    }
  }

  // ── 焦点环
  //
  // ⚠️ 这条判据放宽过两次，两次都是**假阳性**，而假阳性比漏报更伤：
  // 它会训练人往正确的代码上加没用的类，去讨好一个错着的规则。
  //
  // 1. **漫游 tabindex 的容器**：Radix 的 `role="radiogroup"` / `tablist` 等会在
  //    容器上放 `tabindex="0"`，焦点落上去会转交给选中项 —— 环该长在项上，
  //    不在容器上。`/growth` 的归因方式选择组就是这么被报出来的。
  // 2. **outline 写法**：`focus-visible:outline-2 + outline-offset-2 + outline-[var(--ring)]`
  //    是一份完整正确的焦点环（`ui/misc.tsx` 的 StatCard 链接刻意用它：outline 跟随
  //    `rounded-[inherit]`，也不会与卡片自己的阴影抢同一条 box-shadow）。
  //    此前只认 `focus-visible:ring-offset`，于是把它报成「有 ring 无 offset」。
  /** 焦点由子项承担的容器角色（漫游 tabindex），环不该长在容器上 */
  const ROVING = /^(radiogroup|tablist|menu|menubar|listbox|tree|grid|toolbar)$/;
  const focusables = all.filter(
    (el) => el.matches(FOCUSABLE)
      && !el.hasAttribute("disabled")
      && !el.hasAttribute("data-audit-skip")
      && !ROVING.test(el.getAttribute("role") ?? ""),
  );
  for (const el of focusables) {
    const cls = classOf(el);
    // `focus-ring` 是 globals.css 里的 @utility，展开后等价于那串手写的四个类。
    // 扫描器读的是 class 字符串，看不到展开结果，所以要在这里认它 ——
    // 否则「把配方沉到一处」这件正确的事反而会被报成违规。
    if (/(^|\s)focus-ring(\s|$)/.test(cls)) continue;
    const hasRing = /focus-visible:(ring|outline-|shadow)/.test(cls);
    const hasOffset = /focus-visible:(ring|outline)-offset/.test(cls);
    if (!hasRing) {
      findings.push({ comp: compOf(el, label), rule: RULES.focus, detail: "class 里没有 focus-visible:*", sample: sampleOf(el) });
    } else if (!hasOffset) {
      findings.push({ comp: compOf(el, label), rule: RULES.focusOffset, detail: "有环无 offset", sample: sampleOf(el) });
    }
  }

  auditTables(root, findings, label);

  return { findings, scanned: all.length, focusable: focusables.length };
}

/** 同 comp + 同 rule 合并，便于当工作单读 */
export type Grouped = { comp: string; rule: string; count: number; details: string[]; samples: string[] };

export function groupFindings(findings: Finding[]): Grouped[] {
  const map = new Map<string, Grouped>();
  for (const f of findings) {
    const k = `${f.comp}||${f.rule}`;
    const g = map.get(k) ?? { comp: f.comp, rule: f.rule, count: 0, details: [], samples: [] };
    g.count++;
    if (!g.details.includes(f.detail)) g.details.push(f.detail);
    if (g.samples.length < 3) g.samples.push(f.sample);
    map.set(k, g);
  }
  return [...map.values()].sort((a, b) => b.count - a.count || a.comp.localeCompare(b.comp));
}
