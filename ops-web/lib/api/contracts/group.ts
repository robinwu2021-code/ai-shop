// 覆盖范围：商家团（P-8.1）与求团撮合（P-8.2）。
import type { DemandOrder, GroupCampaign, GroupStatus, Page, Quote } from "@/lib/types";
import type { DemandQ, GroupQ, PageQ } from "../query";

export interface GroupApi {
  listGroupCampaigns(q?: GroupQ): Promise<Page<GroupCampaign>>;
  /** 团模板审核（P-8.1.1）：起团人数 ≥2、团购价必须低于原价。 */
  auditGroupCampaign(groupNo: string, pass: boolean, reason?: string): Promise<GroupCampaign>;
  setGroupStatus(groupNo: string, status: GroupStatus): Promise<GroupCampaign>;

  listDemands(q?: DemandQ): Promise<Page<DemandOrder>>;
  listQuotes(q?: PageQ & { demandNo?: string }): Promise<Page<Quote>>;
  /**
   * 人肉指派商家报价（P-8.2.2，初期靠运营撮合）。
   * 同一需求同一商家只能有一条报价；毁约 ≥3 次的商家禁止报价（ADR-003 信用约束）。
   */
  assignQuote(v: { demandNo: string; merchantNo: string; price: number; minQty: number; validTo: string }): Promise<Quote>;
  /** 改价（P-8.2.4）：留痕并公示，超过阈值禁止再改。 */
  changeQuotePrice(quoteNo: string, price: number): Promise<Quote>;
  /** 标记毁约（P-8.2.5）：累计进商家信用档案。 */
  markQuoteBreached(quoteNo: string): Promise<Quote>;
}
