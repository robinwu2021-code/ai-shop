// 覆盖范围：风控（P-16.2）。
import type { BlacklistEntry, Page, RiskEvent, RiskRule, RiskType, SubjectType } from "@/lib/types";
import type { BlacklistQ, RiskQ } from "../query";

export interface RiskApi {
  listRiskEvents(q?: RiskQ): Promise<Page<RiskEvent>>;
  /** 事件处置（P-16.2.1–3）：确认或排除，都要写结论。 */
  decideRiskEvent(eventNo: string, confirmed: boolean, verdict: string): Promise<RiskEvent>;

  listBlacklists(q?: BlacklistQ): Promise<Page<BlacklistEntry>>;
  /**
   * 拉黑（P-16.2.4）。**必须带原因与到期时间** ——
   * 无期限拉黑没有申诉出口，那是产品事故不是风控严格。
   */
  addBlacklist(v: { subjectType: SubjectType; subject: string; reason: string; until: string }): Promise<BlacklistEntry>;
  /**
   * 解禁申诉裁决：接受则解除拉黑，两种结论都要写说明。
   * ⚠️ 刻意不叫 `decideAppeal` —— 评价域（P-13.1.3）已经占了那个名字，
   * 那是「商家申诉差评」，这是「被拉黑者申诉解禁」，两件事。
   * 契约是**扁平合并**的（见 lib/api/contract.ts），同名会直接把另一个域的方法覆盖掉。
   */
  decideBlacklistAppeal(blackNo: string, accept: boolean, verdict: string): Promise<BlacklistEntry>;

  listRiskRules(): Promise<RiskRule[]>;
  /** 拦截规则（P-16.2.5）。阈值必须 > 0 —— 0 等于全量拦截。 */
  saveRiskRule(type: RiskType, threshold: number, autoBlock: boolean): Promise<RiskRule>;
}
