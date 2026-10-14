// 覆盖范围：售后治理（P-6.1）。
import type { AfterSale, FastRefundRule, Liability, Page } from "@/lib/types";
import type { AfterSaleQ } from "../query";

export interface AfterSaleApi {
  listAfterSales(q?: AfterSaleQ): Promise<Page<AfterSale>>;
  /**
   * 平台介入裁决（`ARBITRATING` 的唯一出口）。
   * **责任方与裁决说明必填**——判了责任才谈得上赔付归属，裁决说明双方都会看到。
   * `refund=true` 支持用户（推进到退款），`false` 维持商家决定（关闭）。
   * 退款金额不在这里改：裁决只决定退不退，金额还是申请时那笔。
   */
  decideAfterSale(v: {
    afterSaleNo: string;
    refund: boolean;
    liability: Liability;
    verdict: string;
  }): Promise<AfterSale>;

  getFastRefundRule(): Promise<FastRefundRule>;
  /**
   * 售后规则（P-6.1.2）：金额上限 > 0、各时限 ≥ 1。
   *
   * 后端按**缺字段保持原值**处理，所以少发几项不会把它们清成 0 ——
   * 这一屏整份发，将来拆成两屏也不必互相带着对方的值。
   */
  saveFastRefundRule(v: Omit<FastRefundRule, "updatedAt" | "updatedBy">): Promise<FastRefundRule>;
}
