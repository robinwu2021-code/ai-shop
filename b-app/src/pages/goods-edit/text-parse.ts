/**
 * 文字识别结果 → 商品字段的**落点计算**（纯函数）。
 *
 * <p><b>为什么抽出来</b>：这段逻辑原先长在 `index.vue` 的 `applyTextParse` 里，
 * 而 b-app 的单测只收 `tests/` 下的纯函数（`vitest.config.mts` 不装 vue 插件、没有 DOM）。
 * 于是「限购地区到底落没落」这种事**没有一条测试看得见** —— 而它恰恰是出过报障的那一条：
 * 后端把省码回来了、端上亮了一枚 chip、值没写进商品，看起来像已经生效。
 *
 * <p>这里只**算**要改什么（返回一份 plan），不碰任何 ref —— 应用那一步在 `index.vue`，
 * 一行一行照 plan 写。参数（`params`）不在这里：它有自己的落点函数 `applyParamPicks`
 * 与自己的测试（`tests/param-picks.test.ts`），同一个后端结果走同一条路。
 */
import type { GoodsTextParse } from "@/api/requests";

/** 算落点时需要知道的「现在是什么样」。只读，不改 */
export interface TextParseTarget {
  /** 多规格（groups 非空）。多规格时价格不直落第一行 */
  multi: boolean;
  /** 「统一价格」输入框现在的值 */
  bulkPrice: string;
  /** SKU 行：只看价与标称重量这两格 */
  rows: { priceMajor: Record<string, string>; nominalGram: string }[];
  /** 当前市场（价存在 priceMajor[market] 下） */
  market: string;
  /** 商家已经勾上的限购省码 */
  restrictedRegions: string[];
  /** 已有的规格维度名 */
  groupNames: string[];
}

/** 复核面上的一行：哪一格 · 填了什么。`labelKey` 是 i18n 键，解析在调用方 */
export interface ParseItem {
  labelKey: string;
  value: string;
}

/** 要改什么。每个字段都是「有值才改」，`undefined` = 这一项不动 */
export interface TextParsePlan {
  /** 履约里加 EXPRESS */
  addExpress: boolean;
  /** 多规格：写进「统一价格」，由人点「统一填入」 */
  bulkPrice?: string;
  /** 单规格：直落第一行 */
  rowPrice?: string;
  /** 标称重量（克），填进所有**空着**的行 */
  nominalGram?: string;
  /** 限购省码的**并集**结果。`undefined` = 没有新的省要加 */
  restrictedRegions?: string[];
  /** 识别到、但还没加进规格的维度。**只列出来等人点** */
  specPicks: { name: string; options: string[] }[];
  /** 改了哪几项，i18n key 的后缀（给 toast 用） */
  changed: string[];
  /** 复核面要列的行（与 `changed` 同序，多带一个值） */
  items: ParseItem[];
}

/**
 * 原文里的重量候选（「4.5斤」「140g+」）→ 标称重量的克数。
 *
 * <p>一段话里常有两个重量：净重和单果重。标称重量是**包裹重**（算运费用的），
 * 所以取其中**最大**的那个 —— 4.5 斤的箱子里装着 140g 的果子，寄走的是箱子。
 * 认不出单位的直接丢掉，不猜。
 */
export function gramsOf(weights: string[]): number | null {
  // kg 要排在 g 前面：「4.5kg」里也有一个 g
  const units: [RegExp, number][] = [[/kg|千克|公斤/i, 1000], [/斤/, 500], [/g|克/i, 1]];
  let max = 0;
  for (const w of weights) {
    const num = Number((w.match(/[\d.]+/) ?? [])[0]);
    if (!num) continue;
    const u = units.find(([re]) => re.test(w));
    if (u) max = Math.max(max, Math.round(num * u[1]));
  }
  return max || null;
}

/** 分 → 元，两位小数。与页面上其它处同一种写法 */
function yuan(minor: number): string {
  return (minor / 100).toFixed(2);
}

/**
 * 算出这次识别要改哪些字段。
 *
 * <p>三条不要改掉的原则：
 * <ul>
 *   <li><b>价格永远以规则为准</b> —— `pricesMinor` 是后端正则抽的，不取 LLM 的数。
 *       真金白银不交给概率。</li>
 *   <li><b>只填空着的</b> —— 一键覆盖掉商家自己填的没有撤销。</li>
 *   <li><b>不动运费模板</b> —— 它跨整店，识别一件商品不该改它。承运商
 *       （`carriers`）因此只亮提示、不落字段：商品上压根没有这一格。</li>
 * </ul>
 *
 * <p>两处有意**不**自动做：
 * <ul>
 *   <li><b>规格维度不自动加</b>：加一个维度会把价格/库存从一行变成 N 行，
 *       自动做等于在商家打字途中换掉他已填的行结构。「只填空着的」在这里
 *       不成立 —— 维度不是一个值。</li>
 *   <li><b>多规格时价格不直落</b>：直落第一行只填了一行、页面不会说，
 *       另几行就那么空着上架。</li>
 * </ul>
 */
export function planTextParse(r: GoodsTextParse, cur: TextParseTarget): TextParsePlan {
  const plan: TextParsePlan = { addExpress: false, specPicks: [], changed: [], items: [] };
  if (!r.confidence) return plan;

  if (r.fulfillment.includes("EXPRESS")) {
    plan.addExpress = true;
    plan.changed.push("parseExpress");
    plan.items.push({ labelKey: "goods.fulfillment", value: r.carriers[0] ?? "" });
  }

  if (r.pricesMinor.length) {
    const next = yuan(r.pricesMinor[0]!);
    if (cur.multi) {
      if (cur.bulkPrice !== next) {
        plan.bulkPrice = next;
        plan.changed.push("parsePriceBulk");
        plan.items.push({ labelKey: "goods.bulkPrice", value: next });
      }
    } else if (cur.rows[0] && cur.rows[0].priceMajor[cur.market] !== next) {
      plan.rowPrice = next;
      plan.changed.push("parsePrice");
      plan.items.push({ labelKey: "goods.parsePrice", value: next });
    }
  }

  const grams = gramsOf(r.weights);
  if (grams && cur.rows.some((row) => !row.nominalGram.trim())) {
    plan.nominalGram = String(grams);
    plan.changed.push("parseWeight");
    plan.items.push({ labelKey: "goods.nominalGram", value: String(grams) });
  }

  /*
   * 限购地区取**并集**，不覆盖商家手选的：识别只会让范围更保守，
   * 不会悄悄放开一个他已经勾上的省。
   */
  const more = (r.restrictedRegions ?? []).filter((c) => !cur.restrictedRegions.includes(c));
  if (more.length) {
    plan.restrictedRegions = [...cur.restrictedRegions, ...more];
    plan.changed.push("parseRegions");
    plan.items.push({ labelKey: "goods.restrictedLabel", value: more.join(" ") });
  }

  plan.specPicks = (r.specs ?? [])
    .filter((sp) => sp.options.length && !cur.groupNames.includes(sp.name));

  return plan;
}

/**
 * 合出这次识别的**撤销点**。
 *
 * <p>**一串连续识别只有一个撤销点。** 边输边识别停手就跑一次，商家贴一段话能跑好几遍；
 * 撤销要退回的是「我贴这段话之前」，不是「上一次防抖之前」。所以已经有撤销点时
 * **只往 items 里追加**，快照保持第一次那份；没有撤销点、这次又确实改了东西，
 * 才拿当前快照建一个。
 *
 * <p>这次什么都没改就回原值（可能是 `null`）—— 不要凭「识别跑过」就建撤销点，
 * 那会让「撤销」把更早的一次识别也一起退掉。
 */
export function mergeUndo<T extends object, I>(
  prev: (T & { items: I[] }) | null,
  snapshot: T,
  items: I[],
): (T & { items: I[] }) | null {
  if (!items.length) return prev;
  if (prev) return { ...prev, items: [...prev.items, ...items] };
  return { ...snapshot, items };
}
