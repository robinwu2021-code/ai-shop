// 覆盖范围：商家治理（P-11.1 入驻审核 / 档案 / 认证标 / 封禁）。
// ⚠️ 契约禁止 delete*：下架商家用 archiveMerchant，封禁用 setMerchantStatus。
import type { AuthCode, Merchant, MerchantStatus, Page, Violation, ViolationAction, ViolationType } from "@/lib/types";
import type { MerchantQ } from "../query";

export interface MerchantApi {
  listMerchants(q?: MerchantQ): Promise<Page<Merchant>>;
  getMerchant(merchantNo: string): Promise<Merchant>;
  /** 审核推进（DRAFT→SUBMITTED→REVIEWING→APPROVED/REJECTED），非法迁移抛错。 */
  setMerchantStatus(merchantNo: string, status: MerchantStatus, remark?: string): Promise<Merchant>;
  /** 认证标授予/撤销（P-11.1.2）。 */
  /**
   * 认证标授予/撤销（P-11.1.2）。
   *
   * 只有已过审、且毁约次数未达上限的商家能拿标 —— 认证标是平台的背书，
   * 挂在一个正在毁约的商家身上，赔的是平台的信用。
   */
  setMerchantVerified(merchantNo: string, verified: boolean): Promise<Merchant>;
  archiveMerchant(merchantNo: string): Promise<Merchant>;
  unarchiveMerchant(merchantNo: string): Promise<Merchant>;

  // ── 类目授权（P-11.1.3）────────────────────────────────────────

  /** 授权码目录。按码授权而不是按类目节点：类目树会重构，"能不能卖菜"不会。 */
  listAuthCodes(): Promise<AuthCode[]>;

  /**
   * 改一个商家的类目授权范围。
   *
   * 校验都在 mock 层：
   * - 只有 `APPROVED` 的商家能授权 —— 没过审就授权等于提前放行；
   * - 需要资质的码，商家必须已有对应资质；
   * - **不能把授权撤空**：商家会静默失去上架能力，要停就走封禁/归档，那是明示的动作；
   * - **该码下还有在售商品的不能撤**：撤了架上还挂着那类商品，谁也说不清它算不算违规。
   */
  setMerchantAuthCodes(v: { merchantNo: string; codes: string[]; reason: string }): Promise<Merchant>;

  // ── 违规处置与信用档案（P-11.1.4 / 11.1.5）─────────────────────

  listViolations(q?: { merchantNo?: string }): Promise<Violation[]>;

  /**
   * 记一条违规并执行处置。
   *
   * - `detail` 必填：没有事实的处置在申诉时站不住；
   * - `BREACH`（毁约）**只有这一类**计入 `breachCount` —— 别的违规也计的话，
   *   ADR-003 里"毁约达阈值即限制报价"那条规则的阈值就失去意义了；
   * - `SUSPEND` 会真的把商家推到 `SUSPENDED`，走同一张状态机。
   */
  recordViolation(v: {
    merchantNo: string;
    type: ViolationType;
    action: ViolationAction;
    detail: string;
  }): Promise<Violation>;
}
