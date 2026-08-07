// 覆盖范围：支付管理（P-4.2 支付流水核对 / 掉单补偿 / 关单策略配置）。
import type { CloseRule, Page, ReconDiff, RecoverAction } from "@/lib/types";
import type { PageQ } from "../query";

export type ReconQ = PageQ & { billDate?: string; type?: string; status?: string };

export interface PaymentApi {
  /** 对账差异列表（P-4.2.1）。 */
  listReconDiffs(q?: ReconQ): Promise<Page<ReconDiff>>;

  /**
   * 处置一条差异（P-4.2.1 / 4.2.2）。
   *
   * - `action` 只对 `CHANNEL_ONLY` 有意义（补单 / 退款），其余类型传 undefined；
   * - `resolution` **必填**：没有结论的"已处理"等于没处理，下次同样的差异还得从头查。
   */
  resolveReconDiff(v: {
    diffNo: string;
    action?: RecoverAction;
    resolution: string;
  }): Promise<ReconDiff>;

  /** 忽略一条差异（如渠道手续费导致的分位差）。同样要写清理由。 */
  ignoreReconDiff(v: { diffNo: string; resolution: string }): Promise<ReconDiff>;

  getCloseRule(): Promise<CloseRule>;
  /**
   * 关单策略（P-4.2.3）。
   * 时限有上下限：太短会把正在付款的用户关掉（制造掉单），太长会长期占住库存。
   */
  saveCloseRule(v: Pick<CloseRule, "unpaidMinutes" | "remindBeforeMinutes" | "autoRefundOnLateCallback">): Promise<CloseRule>;
}
