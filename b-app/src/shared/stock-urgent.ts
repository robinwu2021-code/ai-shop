// 进销存里「有人在等」的那几项。**两页共用一份** —— 工作台那张卡与库存页顶部
// 是同一块东西的前缀与全文，各算一份的下场今天就演过：
// 加「在途」时只加在库存页、加「继续盘点」时只加在工作台，
// 于是两块信息各缺对方一半，而两边都不报错。
//
// 这里只回答「有哪些事在等」，**不回答「怎么摆」** ——
// 工作台会在后面补上「进货」把三格填满，库存页不填（写动作在它自己的贴底条里）。
import type { StockSummary } from "@shared/types";
import { ROUTES } from "./nav";

export interface UrgentItem {
  key: string;
  /** i18n 词条 key 与参数由调用方翻译 —— 这一层不碰 i18n，好在测试里直接断言 */
  labelKey: string;
  params?: Record<string, number | string>;
  route: string;
}

/**
 * 顺序是**按等待的代价排**，不是按字母：
 * 在途是货停在路上（有人在仓库那头等着签收），
 * 盘点单开着是账面锁着（期间的销售不计入差异，拖久了差异就解释不清）。
 */
export function urgentStockItems(s: StockSummary | null | undefined): UrgentItem[] {
  if (!s) return [];
  const out: UrgentItem[] = [];
  if ((s.inTransitCount ?? 0) > 0) {
    out.push({
      key: "receive",
      labelKey: "home.inv.receiveN",
      params: { n: s.inTransitCount },
      route: `${ROUTES.stockDocs}?kind=TRANSFER`,
    });
  }
  if (s.openCountNo) {
    out.push({
      key: "resume",
      labelKey: "home.inv.resumeCount",
      route: `${ROUTES.stockCheck}?no=${encodeURIComponent(s.openCountNo)}`,
    });
  }
  return out;
}
