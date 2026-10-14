/**
 * 首页商品流：**一件商品只出一张卡**。
 *
 * 此前首页上半截是「邻里团购」三张团卡、下半截是商品流 —— 同一只香梨在一屏里出现两次，
 * 一次是团卡（团价），一次是商品卡（单买价），用户要自己对出这是同一件东西。
 *
 * 现在把团**并进**它那件商品的卡里：
 *   - 有进行中团的商品 → 团卡形态（团价 + 单买价 + 「K 个团在拼 · 最快还差 M 人」+ 去拼团），
 *     置顶，按最早截止在前 —— 团是限时的，快截的先给人看见；
 *   - 其余商品 → 普通卡，保持后端的顺序（覆盖范围 + 距离）。
 *
 * 团所属的商品不在这一页商品流里（比如商品流只取了前 20 条），这个团就不在首页出卡 ——
 * 首页顶上那一行「邻里团购 · N 个团正在拼 · 全部 ›」仍然数得到它、点得进去。
 */
import type { Goods, GroupBuy } from "@shared/types";

/** 一件商品上所有进行中的团，合成一条给卡片用 */
export interface GoodsGroupSummary {
  /** 进行中的团数 */
  count: number;
  /** 成团人数（取最快成团那一个团的） */
  minCount: number;
  /** 团价（同一商品的团里取最低） */
  groupPrice: number;
  /** 单买价 */
  basePrice: number;
  /** 最快成团的团还差几人 */
  need: number;
  /** 最快成团的那个团 —— 「去拼团」直接进它 */
  groupNo: string;
  /** 最早的截止时刻，排序用 */
  expireAt: number;
}

export interface HomeFeedItem {
  goods: Goods;
  group?: GoodsGroupSummary;
}

/** 首页还能参与的团：没截止、还没满员（满员的也能看，但不再是「来拼」） */
export function joinableGroups(groups: GroupBuy[], now: number): GroupBuy[] {
  return groups.filter((g) => g.expireAt > now && !g.reached);
}

function summarize(gs: GroupBuy[]): GoodsGroupSummary {
  // 「最快成团」= 还差人数最少；并列时先截止的在前
  const lead = [...gs].sort((a, b) => a.need - b.need || a.expireAt - b.expireAt)[0]!;
  return {
    count: gs.length,
    minCount: lead.minCount,
    groupPrice: Math.min(...gs.map((g) => g.groupPrice)),
    basePrice: lead.basePrice,
    need: lead.need,
    groupNo: lead.groupNo,
    expireAt: Math.min(...gs.map((g) => g.expireAt)),
  };
}

export function buildHomeFeed(goods: Goods[], groups: GroupBuy[], now: number): HomeFeedItem[] {
  const byGoods = new Map<string, GroupBuy[]>();
  for (const g of joinableGroups(groups, now)) {
    const list = byGoods.get(g.goodsNo);
    if (list) list.push(g);
    else byGoods.set(g.goodsNo, [g]);
  }
  const withGroup: HomeFeedItem[] = [];
  const plain: HomeFeedItem[] = [];
  const seen = new Set<string>();
  for (const g of goods) {
    // 商品流偶有重复（分页边界）—— 一件商品一张卡是这个函数的承诺，在这儿兜住
    if (seen.has(g.goodsNo)) continue;
    seen.add(g.goodsNo);
    const gs = byGoods.get(g.goodsNo);
    if (gs) withGroup.push({ goods: g, group: summarize(gs) });
    else plain.push({ goods: g });
  }
  withGroup.sort((a, b) => a.group!.expireAt - b.group!.expireAt);
  return [...withGroup, ...plain];
}
