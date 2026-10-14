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
 * 现在能不能开一张**新的**盘点单。
 *
 * **有单开着时不能**——那一张已经把每件货当时的账面数锁住了（后端 `open()` 那一刻快照），
 * 而开单之后照常卖。同时开两张，两张锁的是两个时刻的数：
 * 先开那张过账时，会把这中间卖掉的量当成盘亏再扣一遍，
 * **账朝一个方向错，而且不报错**。
 *
 * ⚠️ **后端今天拦不住**：`StockCountServiceImpl.open()` 一句「已经有一张开着」都没查，
 * 而首页的 `openCountNo` 只给**最近的那一张**（`ORDER BY id DESC LIMIT 1`）——
 * 先开的那张当场从界面上消失，只能去单据里翻。这里是端上先把路堵住，
 * 后端那道闸另算（要新错误码，动契约）。
 *
 * **两页共用一份**：库存页的「更多」与工作台那张卡都要按这条让位，
 * 各判各的下场这个文件的头注已经写过一次了。
 */
export function canStartNewCount(s: StockSummary | null | undefined): boolean {
  return !s?.openCountNo;
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
