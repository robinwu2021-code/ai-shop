// 覆盖范围：售后治理（P-6.1）。
import type { AfterSale, AfterSaleStatus, FastRefundRule, Liability, LiabilityShare, Page } from "@/lib/types";
import type { AfterSaleQ } from "../query";

export interface AfterSaleApi {
  listAfterSales(q?: AfterSaleQ): Promise<Page<AfterSale>>;
  /** 状态推进，非法迁移抛错（驳回不是终点，用户可上升平台）。 */
  setAfterSaleStatus(asNo: string, status: AfterSaleStatus): Promise<AfterSale>;
  /**
   * 平台介入裁决（P-6.1.3 + 6.1.4）。
   * **必须同时给出责任方与三方赔付比例**（和为 100）—— 判了责任才谈得上赔付归属，
   * 分两步做会出现「裁决完了忘了判责」的空档。
   */
  decideAfterSale(v: {
    asNo: string;
    liability: Liability;
    share: LiabilityShare;
    verdict: string;
    /** 同意退款金额（分），不得超过订单实付 */
    amount: number;
  }): Promise<AfterSale>;

  getFastRefundRule(): Promise<FastRefundRule>;
  /** 极速退阈值（P-6.1.2）：金额上限 > 0、时限 ≥ 1 小时。 */
  saveFastRefundRule(v: Pick<FastRefundRule, "enabled" | "maxAmount" | "withinHours" | "categories">): Promise<FastRefundRule>;
}
