// 覆盖范围：门店主页治理（P-10.1）—— 一期主获客路径的平台侧。
import type { Page, StoreAcquisition, StorePageAudit, StoreQrcode, StoreTemplate } from "@/lib/types";
import type { PageQ, StoreAuditQ } from "../query";

export interface StoreApi {
  listStoreAudits(q?: StoreAuditQ): Promise<Page<StorePageAudit>>;
  /** 审核裁决（P-10.1.2）。**驳回必须带原因**：原因原样进商家 B 端。 */
  decideStoreAudit(auditNo: string, pass: boolean, reason?: string): Promise<StorePageAudit>;
  /** 店铺码（P-10.1.3），供 BD 批量导出去印刷。 */
  listStoreQrcodes(q?: PageQ): Promise<Page<StoreQrcode>>;
  /** 门店获客效果（P-10.1.4）。 */
  listStoreAcquisition(q?: PageQ): Promise<Page<StoreAcquisition>>;

  // ── 主页模板配置（P-10.1.1）────────────────────────────────────

  listStoreTemplates(): Promise<StoreTemplate[]>;

  /**
   * 新建/保存模板。
   *
   * 校验都在 mock 层：
   * - 必选板块（店招）不能停用 —— 关了之后店铺页没有头部，等于一张裸列表；
   * - 启用的板块不能少于 `MIN_ENABLED_SECTIONS`；
   * - 板块 key 不能重复（重复时哪条生效取决于顺序，那是隐性行为）。
   */
  saveStoreTemplate(
    v: Omit<StoreTemplate, "updatedAt" | "updatedBy" | "usedByCount" | "templateNo"> & { templateNo?: string },
  ): Promise<StoreTemplate>;

  /**
   * 启用/停用模板。
   *
   * **正在被店铺使用的模板停不掉** —— 停用会让那些店铺页瞬间失去模板；
   * 默认模板同理，新店开出来就用它。要换模板得先把店迁走。
   */
  setStoreTemplateEnabled(templateNo: string, enabled: boolean): Promise<StoreTemplate>;
}
