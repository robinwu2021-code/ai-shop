// 全量对比度扫描：5 套皮肤 × 明暗 = 10 组，一次跑完。
//
// **为什么不复用页面上那些 `<Probe>`**：探针是 React 组件，只在页面自身的开关
// 触发 `PreviewVersionCtx` 变化时才重量；从外部脚本改 `<html>` 属性不会触发它，
// 于是"批量验证"这件事一直做不成 —— 上一轮试过一次，还得到过"10 组全通过"的
// **假阴性**（正则没匹配到任何读数，空集被当成了没有失败）。
//
// 这里直接在 DOM 上量：切一组 → 等样式生效 → 扫一遍 → 记录 → 切下一组 → 最后还原。
// 与 `<Probe>` 共用同一套 `measure/aaThreshold`，所以两边的数字一定一致。
import { measure, aaThreshold, csOf, beginColorBatch, endColorBatch } from "./color";

export interface SweepFail {
  /** 皮肤 key */
  skin: string;
  dark: boolean;
  /** 归属组件（最近的 [data-comp]） */
  comp: string;
  ratio: number;
  need: number;
  /** 定位线索：标签 + 前 24 个字 */
  sample: string;
}

export interface SweepResult {
  fails: SweepFail[];
  /** 一共量了多少个文本节点（每组） */
  measuredPerCombo: number;
  combos: number;
}

/**
 * 等两帧：换肤是改 CSS 变量，样式要下一帧才真正生效，当帧量到的是旧色。
 *
 * ⚠️ **必须带超时兜底**：标签页/面板切到后台时 `requestAnimationFrame` 整个不触发，
 * 整轮扫描会停在原地不动 —— 而界面上只是进度不走，看不出是卡住还是慢
 * （`app/dev/pages` 的页面扫描踩过一次，停在 2/21）。
 * 也要用**目标窗口**的 rAF：跨 iframe 时父窗口的帧与它不是一回事。
 */
const nextFrames = (win: Window = window) =>
  new Promise<void>((r) => {
    let done = false;
    const fin = () => { if (!done) { done = true; r(); } };
    win.requestAnimationFrame(() => win.requestAnimationFrame(fin));
    setTimeout(fin, 120);
  });

/**
 * 扫描期间**关掉所有过渡/动画**。
 *
 * 不关的代价是实测出来的：控件普遍带 `transition-colors 180ms`，切完皮肤两帧后
 * 量到的是**过渡中途的插值色**，会得出 1.23:1 这种不可能的读数 —— 比没有数字更糟，
 * 因为它看起来像个真缺陷。关掉之后颜色是终值，读数才可信。
 */
function freezeTransitions(doc: Document): () => void {
  const style = doc.createElement("style");
  style.textContent = "*,*::before,*::after{transition:none!important;animation:none!important}";
  doc.head.appendChild(style);
  return () => style.remove();
}

/**
 * 该不该量这个元素。
 *
 * 跳过三类，都是**刻意**的、不是偷懒：
 * 1. disabled —— WCAG 明确不对禁用态做对比度要求
 * 2. opacity < 1 —— 半透明是"弱化"的表达手段，本页已有专门一栏讨论它的代价
 * 3. `data-audit-skip` —— 样例里故意演示"错误写法"的那些
 */
function shouldMeasure(el: Element): boolean {
  if (el.closest("[data-audit-skip]")) return false;
  if (el.closest("[disabled],[aria-disabled='true']")) return false;
  const text = [...el.childNodes]
    .filter((n) => n.nodeType === Node.TEXT_NODE)
    .map((n) => n.textContent ?? "")
    .join("")
    .trim();
  if (!text) return false;
  const cs = csOf(el);
  if (parseFloat(cs.opacity) < 1) return false;
  if (cs.visibility === "hidden" || cs.display === "none") return false;
  return true;
}

function compOf(el: Element, fallback = "(未归属)"): string {
  // 真实页面没有 data-comp 标记，回落成路由名 —— 否则归并后是一坨看不出该开哪一页的清单
  return el.closest("[data-comp]")?.getAttribute("data-comp") ?? fallback;
}

function sampleOf(el: Element): string {
  const t = (el.textContent ?? "").replace(/\s+/g, " ").trim();
  return `<${el.tagName.toLowerCase()}> ${t.length > 24 ? t.slice(0, 24) + "…" : t}`;
}

/**
 * 扫描面。与 `audit.ts` 同一套判据：
 * - `specimens`（组件画廊）：只看 `[data-specimen]` 里的组件实例
 * - `subtree`（真实业务页）：整棵子树 —— 业务页没有 data-specimen 标记，
 *   按前一种口径扫出来**恒为空**，而空集看起来和「全都合规」一模一样
 */
export type SweepScope = "specimens" | "subtree";

function targets(root: ParentNode, scope: SweepScope): Element[] {
  if (scope === "subtree") return [...root.querySelectorAll("*")];
  return [...root.querySelectorAll("[data-specimen]")].flatMap((s) => [...s.querySelectorAll("*")]);
}

/** 扫当前这一组（不改 DOM 状态）。 */
function scanOnce(skin: string, dark: boolean, root: ParentNode, scope: SweepScope,
                  label?: string): { fails: SweepFail[]; measured: number } {
  const fails: SweepFail[] = [];
  let measured = 0;
  {
    for (const el of targets(root, scope)) {
      if (!shouldMeasure(el)) continue;
      measured++;
      const m = measure(el);
      // 解析不出色值时**不当作通过**，但也不报成失败 —— 它是"没量到"，另一回事
      if (m.ratio == null) continue;
      const need = aaThreshold(el);
      if (m.ratio < need) {
        fails.push({ skin, dark, comp: compOf(el, label), ratio: m.ratio, need, sample: sampleOf(el) });
      }
    }
  }
  return { fails, measured };
}

/**
 * 跑完整个矩阵。跑完**一定还原**调用前的皮肤与明暗（用 try/finally，
 * 中途抛错也不会把使用者的界面留在某个随机皮肤上）。
 */
export interface SweepTarget {
  /** 要切皮肤的那个文档。跨 iframe 扫真实页面时传 `iframe.contentDocument` */
  doc?: Document;
  /** 扫描根。默认整个文档 */
  root?: ParentNode;
  scope?: SweepScope;
  /** 没有 `data-comp` 时的归属回落（真实页面传路由） */
  label?: string;
}

export async function runContrastSweep(
  skins: readonly string[],
  onProgress?: (done: number, total: number) => void,
  target: SweepTarget = {},
): Promise<SweepResult> {
  const doc = target.doc ?? document;
  const win = doc.defaultView ?? window;
  const root = target.root ?? doc;
  const scope = target.scope ?? "specimens";
  const el = doc.documentElement;
  const prevSkin = el.dataset.theme;
  const prevDark = el.classList.contains("dark");

  const fails: SweepFail[] = [];
  let measuredPerCombo = 0;
  const total = skins.length * 2;
  let done = 0;
  const unfreeze = freezeTransitions(doc);

  try {
    for (const dark of [false, true]) {
      el.classList.toggle("dark", dark);
      for (const skin of skins) {
        el.dataset.theme = skin;
        await nextFrames(win);
        // 每组重开一次缓存：组内颜色不变可以复用，组间必须失效 ——
        // 不失效就是拿上一组的背景色算这一组，比值全错而且错得很像真的
        beginColorBatch();
        const r = scanOnce(skin, dark, root, scope, target.label);
        fails.push(...r.fails);
        measuredPerCombo = Math.max(measuredPerCombo, r.measured);
        onProgress?.(++done, total);
      }
    }
  } finally {
    if (prevSkin) el.dataset.theme = prevSkin; else delete el.dataset.theme;
    el.classList.toggle("dark", prevDark);
    endColorBatch();
    await nextFrames(win);
    unfreeze();
  }

  return { fails, measuredPerCombo, combos: total };
}

/** 同一个 comp+sample 在多组里都挂 → 合成一行，省得同一处问题刷十遍。 */
export function groupFails(fails: SweepFail[]) {
  const map = new Map<string, { comp: string; sample: string; worst: number; need: number; combos: string[] }>();
  for (const f of fails) {
    const key = `${f.comp}||${f.sample}`;
    const combo = `${f.dark ? "暗" : "浅"}·${f.skin}`;
    const cur = map.get(key);
    if (cur) {
      cur.worst = Math.min(cur.worst, f.ratio);
      cur.combos.push(combo);
    } else {
      map.set(key, { comp: f.comp, sample: f.sample, worst: f.ratio, need: f.need, combos: [combo] });
    }
  }
  return [...map.values()].sort((a, b) => a.worst - b.worst);
}
