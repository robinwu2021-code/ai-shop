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
  /** SKU 行：只看价这一格 */
  rows: { priceMajor: Record<string, string> }[];
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
  /**
   * 值要怎么显示。`regions` = 一串省级 regionCode，**展示前必须换成省名** ——
   * 表单上那一行写的是「新疆维吾尔自治区、西藏自治区」，复核面若直接印
   * 「65 54 46」，同一个值在同一屏上有两种说法，商家没法核对。
   * 换名要查 `PROVINCE_NAME_BY_CODE`，那是展示层的事，不放进纯函数。
   */
  kind?: "regions";
}

/** 要改什么。每个字段都是「有值才改」，`undefined` = 这一项不动 */
export interface TextParsePlan {
  /** 履约里加 EXPRESS */
  addExpress: boolean;
  /** 多规格：写进「统一价格」，由人点「统一填入」 */
  bulkPrice?: string;
  /** 单规格：直落第一行 */
  rowPrice?: string;
  /** 限购省码的**并集**结果。`undefined` = 没有新的省要加 */
  restrictedRegions?: string[];
  /** 识别到、但还没加进规格的维度。**只列出来等人点** */
  specPicks: { name: string; options: string[] }[];
  /** 改了哪几项，i18n key 的后缀（给 toast 用） */
  changed: string[];
  /** 复核面要列的行（与 `changed` 同序，多带一个值） */
  items: ParseItem[];
}

/*
 * **不再从重量猜标称重量**（TDD-商品快速录入-品类感知与逐项确认 §1）。
 *
 * <p>此前这里有一个 `gramsOf()`：把原文里所有重量取**最大**的那个填进 SKU 的标称重量，
 * 理由是「寄走的是 4.5 斤的箱子，不是 140g 的果子」。那个推理只对了一半 ——
 * 标称重量用来估**运费**，运费该按**毛重**（带箱）算，而「净重 4.5 斤」是净重。
 * 填进去运费会估低，而且界面上没有任何提示。
 *
 * <p>rule 层回来的 `weights` 是一串**没有角色**的原文（「140g+」「4.5斤」），
 * 分不出哪个是单果重、哪个是净重、哪个是毛重。分不出就不猜 ——
 * 角色要等品类的标准核心参数来定（P3）。
 */

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
    /*
     * 值是**落进去的那个字段**，不是承运商。写「配送方式 圆通」是假话 ——
     * 圆通没进任何字段（商品上没有承运商这一格），落进去的是「快递配送」。
     * 复核面列的是「我改了什么」,印一个没被改的值会让人去找它在哪儿。
     */
    plan.items.push({ labelKey: "goods.fulfillment", value: "goods.fulfillmentType.EXPRESS" });
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


  /*
   * 限购地区取**并集**，不覆盖商家手选的：识别只会让范围更保守，
   * 不会悄悄放开一个他已经勾上的省。
   */
  const more = (r.restrictedRegions ?? []).filter((c) => !cur.restrictedRegions.includes(c));
  if (more.length) {
    plan.restrictedRegions = [...cur.restrictedRegions, ...more];
    plan.changed.push("parseRegions");
    plan.items.push({ labelKey: "goods.restrictedLabel", value: more.join(","), kind: "regions" });
  }

  plan.specPicks = (r.specs ?? [])
    .filter((sp) => sp.options.length && !cur.groupNames.includes(sp.name));

  return plan;
}

/**
 * 合出这次识别的**撤销点**。
 *
 * <p>**一串连续识别只有一个撤销点。** 商家常是识别一次、改几个字、再点一次；
 * 撤销要退回的是「我第一次识别之前」，不是「上一次点识别之前」。所以已经有撤销点时
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

/** 录入方式，与后端 `prd_goods_revision.entry_source` 同一套取值 */
export type EntrySource = "MANUAL" | "QUICK_TEXT" | "ZIP" | "IMAGE";

/**
 * 录入方式**只升不降**：压缩包 > 图片识别 > 快速录入 > 手填。
 *
 * <p>压缩包排最前是因为它顺带会触发后两者（带回的 txt 落进识别框、封面图自动跑
 * 图片识别）。按「最后发生的那件事」记就全变成快速录入了，而商家心里做的是
 * 「导了个压缩包」—— 历史里那一列是给人回答「这批参数哪来的」，要记最初那一下。
 */
export function raiseEntry(current: EntrySource, next: EntrySource): EntrySource {
  const rank: Record<EntrySource, number> = { MANUAL: 0, QUICK_TEXT: 1, IMAGE: 2, ZIP: 3 };
  return rank[next] > rank[current] ? next : current;
}

/**
 * 撤销之后的录入方式。
 *
 * <p>文字全撤了就不该还记成「快速录入」—— 历史里那一列会说谎。
 * 退回 MANUAL 而不是退回上一个值：图片识别与压缩包有自己的痕迹
 * （图还在、参数还在），只有文字这一路是真的被撤干净了。
 */
export function entryAfterUndo(current: EntrySource): EntrySource {
  return current === "QUICK_TEXT" ? "MANUAL" : current;
}

/**
 * 确认区的一行（TDD-商品快速录入-品类感知与逐项确认 P2 · AC3）。
 *
 * <p>**一个识别出来的东西就是一行**：勾不勾、值是什么、落到哪个字段。
 * 参数行的落点可以改（「140g」落到单果重量还是净含量，商家说了算）。
 *
 * <p>点「识别文字」之后**先进这里，不写表单** —— 上一轮放弃「先确认再填入」的唯一理由
 * （边输边识别下每打几个字就弹一次）在改成点击之后已经不存在了。
 */
export interface Candidate {
  /** 稳定键：fulfillment / price / bulkPrice / regions / param:<序号> */
  key: string;
  kind: "fulfillment" | "price" | "bulkPrice" | "regions" | "param";
  /** 落到哪个字段的名字。非参数行是 i18n 键；参数行是标准参数名（已是人话） */
  target: string;
  /** 要填进去的值。履约行是 i18n 键；限购地区行是省码逗号串（展示层换省名） */
  value: string;
  /** 这一格**现在**的值。有值 = 勾上会覆盖它，确认区要写出来 */
  before?: string;
  /** 参数行：落到的维度号 */
  dimNo?: string;
  /** 参数行：是否对到了本品类的标准参数。false = 自由参数，要人来指定落点 */
  mapped?: boolean;
  /** 限购地区行：并入之后的全集 */
  regions?: string[];
  /**
   * 参数行：会被**替换掉**的同义旧自由参数（「净重 4.5斤」）。
   *
   * <p>商家手工建过「净重」、或早先识别留下的 {dimNo:"净重"} —— 落标准参数「净含量」时
   * 不处理它的话，两行会并列出现在参数卡里。**写出来交给他勾**，不静默删。
   */
  replaces?: { dimNo: string; label: string };
  checked: boolean;
}

/** 算确认区时需要知道的「现在是什么样」 */
export interface CandidateCurrent {
  /** 履约里已经有快递 —— 有了就不列这一行（没东西可改） */
  hasExpress: boolean;
  /** 单规格时第一行现在的价；空串 = 还没填 */
  priceBefore: string;
  /** 「统一价格」框现在的值 */
  bulkBefore: string;
  /** 已填的参数：维度号 → 值 */
  params: Record<string, string | undefined>;
  /** 本品类的标准参数维度号。参数行据此判 mapped */
  propDimNos: string[];
  /**
   * **自由参数**的键：维度号就是它自己的名字、没有 code（{dimNo:"净重", name:"净重"}）。
   *
   * <p>不能用「不在本品类清单里」来判 —— 产地 SD_ORIGIN 就不在水果的清单里，
   * 那样判会把一个标准参数当成旧自由参数替换掉（测试抓到的）。可选：不传 = 没有自由参数。
   */
  freeKeys?: string[];
}

/**
 * 识别结果 → 确认区的行。
 *
 * <p>**默认勾不勾只有一条规矩：这一格已经有值就不勾**。勾上会覆盖他自己填的，
 * 那得他来决定 —— 所以把「原 15.00」写在值旁边，让他看见覆盖的是什么。
 * 此前直接填的时候，售价会从 15 静默变成 10（生产上实测到），而界面上只有一条 toast。
 *
 * <p>参数行另有一条：**对不上标准参数的默认不勾**（mapped=false）—— 它要先指定落点。
 */
export function buildCandidates(
  plan: TextParsePlan,
  params: { dimNo: string; name: string; label: string; rawName?: string }[],
  cur: CandidateCurrent,
): Candidate[] {
  const rows: Candidate[] = [];
  if (plan.addExpress && !cur.hasExpress) {
    rows.push({
      key: "fulfillment", kind: "fulfillment",
      target: "goods.fulfillment", value: "goods.fulfillmentType.EXPRESS", checked: true,
    });
  }
  if (plan.rowPrice !== undefined) {
    const before = cur.priceBefore.trim() || undefined;
    rows.push({
      key: "price", kind: "price", target: "goods.parsePrice",
      value: plan.rowPrice, before, checked: !before,
    });
  }
  if (plan.bulkPrice !== undefined) {
    const before = cur.bulkBefore.trim() || undefined;
    rows.push({
      key: "bulkPrice", kind: "bulkPrice", target: "goods.bulkPrice",
      value: plan.bulkPrice, before, checked: !before,
    });
  }
  if (plan.restrictedRegions !== undefined) {
    const added = plan.items.find((it) => it.kind === "regions")?.value ?? "";
    rows.push({
      key: "regions", kind: "regions", target: "goods.restrictedLabel",
      value: added, regions: plan.restrictedRegions, checked: true,
    });
  }
  params.forEach((p, i) => {
    const mapped = cur.propDimNos.includes(p.dimNo);
    const before = cur.params[p.dimNo] || undefined;
    rows.push({
      key: `param:${i}`, kind: "param", target: p.name, value: p.label,
      dimNo: p.dimNo, mapped, before, checked: mapped && !before,
      replaces: mapped ? legacyFreeParam(p, cur) : undefined,
    });
  });
  return rows;
}

/**
 * 找同义的**旧自由参数**：键是自由参数（见 `freeKeys`）、不是这次要落的那个，
 * 而名字是原文叫法（净重）或标准名（单果重量）。
 *
 * <p>只认这两种名字，不做模糊匹配 —— 替错一个比漏替一个更糟：
 * 漏了顶多两行并列，商家看得见；替错了是他手工填的东西被换掉。
 */
function legacyFreeParam(
  p: { dimNo: string; name: string; rawName?: string },
  cur: CandidateCurrent,
): { dimNo: string; label: string } | undefined {
  for (const key of [p.rawName, p.name]) {
    if (!key || key === p.dimNo || !(cur.freeKeys ?? []).includes(key)) continue;
    const label = cur.params[key];
    if (label) return { dimNo: key, label };
  }
  return undefined;
}

/**
 * 改一个参数行的落点（「140g」不是净含量，是单果重量）。
 *
 * <p>改完就算对上了（mapped=true）；**默认勾上**，除非那个参数已经有值 ——
 * 与 {@link buildCandidates} 同一条规矩：有值不替他覆盖。
 */
export function retarget(
  c: Candidate,
  dim: { dimNo: string; name: string },
  curParams: Record<string, string | undefined>,
): Candidate {
  const before = curParams[dim.dimNo] || undefined;
  // replaces 跟着原文叫法走，不跟落点：「净重」改落到毛重，被替换的旧「净重」还是那一条
  return { ...c, dimNo: dim.dimNo, target: dim.name, mapped: true, before, checked: !before };
}
