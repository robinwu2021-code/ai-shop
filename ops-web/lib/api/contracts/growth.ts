// 覆盖范围：归因引擎（P-9.1）与裂变活动（P-9.2）。
import type { AttributionRule, AttributionTrace, FissionCampaign, Page } from "@/lib/types";
import type { PageQ, TraceQ } from "../query";

export interface GrowthApi {
  getAttributionRule(): Promise<AttributionRule>;
  /**
   * 归因规则（P-9.1.1/9.1.2/9.1.5）。
   * 优先级必须是**全序**（三个来源不重不漏）；窗口期 1–90 天；新客因子至少一个。
   */
  saveAttributionRule(v: Pick<AttributionRule, "priority" | "windowDays" | "conflictPolicy" | "newUserFactors">): Promise<AttributionRule>;
  /** 归因链路查询与审计（P-9.1.3）。 */
  listAttributionTraces(q?: TraceQ): Promise<Page<AttributionTrace>>;

  listFissionCampaigns(q?: PageQ): Promise<Page<FissionCampaign>>;
  /** 邀请有礼（P-9.2.1）。**奖励只能是券**（ADR-004：不存在现金激励）。 */
  saveFissionCampaign(v: Pick<FissionCampaign, "fissionNo" | "name" | "rewardType" | "couponNo" | "inviterCount" | "inviteeCount">): Promise<FissionCampaign>;
  setFissionEnabled(fissionNo: string, enabled: boolean): Promise<FissionCampaign>;
}
