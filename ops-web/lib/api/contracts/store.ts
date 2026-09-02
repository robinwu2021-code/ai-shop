// 覆盖范围：门店主页治理（P-10.1）—— 一期主获客路径的平台侧；
// 以及门店档案与经营状况（P-11.2.1）—— 平台看一家店的全貌。
import type { Page, StoreAcquisition, StoreGovern, StoreGovernDetail, StorePageAudit, StoreQrcode, StoreStats, StoreTemplate } from "@/lib/types";
import type { AcquisitionQ, QrcodeQ, StoreAuditQ, StoreQ } from "../query";

export interface StoreApi {
  // ── 门店档案（P-11.2.1）——**已接真后端** `/ops/stores`

  /** 跨主体门店检索。含停用与强制下线的店 —— 治理视角更不能看不见 */
  listStores(q?: StoreQ): Promise<Page<StoreGovern>>;

  /** 门店档案详情：门面 + 配送规则 + 经营模式 + 收款商户号。 */
  getStore(storeNo: string): Promise<StoreGovernDetail>;

  /** 门店经营状况：今日/本月订单与 GMV，外加待发货/待自送/缺货三项待办堆积。 */
  getStoreStats(storeNo: string): Promise<StoreStats>;

  /**
   * 解除门店强制下线，恢复被平台压下的货架行。
   *
   * **只有 `SUSPENDED` 的店可以调** —— 商家自助停用（READONLY）的店由商家自己开回来，
   * 平台替他开等于替他做经营决定。压下那一侧走违规处置的 `STORE_OFFLINE`。
   */
  restoreStore(storeNo: string): Promise<StoreGovern>;

  listStoreAudits(q?: StoreAuditQ): Promise<Page<StorePageAudit>>;
  /** 审核裁决（P-10.1.2）。**驳回必须带原因**：原因原样进商家 B 端。 */
  decideStoreAudit(auditNo: string, pass: boolean, reason?: string): Promise<StorePageAudit>;
  /**
   * 店铺码（P-10.1.3），供 BD 批量导出去印刷。
   *
   * `scanCount` 取 `from`/`to` 区间内的扫码次数（不传取最近 30 天）；
   * `printed` 是**累计**印量，与区间无关 —— 它回答的是「一共印过多少」。
   */
  listStoreQrcodes(q?: QrcodeQ): Promise<Page<StoreQrcode>>;

  /**
   * 给这家门店发码。<b>幂等</b>：已经有码就把原码给回来 ——
   * 重复点不会换码，换码要走 {@link reissueStoreQrcode}。
   */
  issueStoreQrcode(v: { merchantNo: string; storeNo?: string }): Promise<{ storeCode: string }>;

  /**
   * <b>换码：旧码当场失效</b>，已经贴在店里的物料全部变成死链。
   * 所以 `reason` 必填 —— 这一步的代价在线下，而线上只是一次点击。
   */
  reissueStoreQrcode(v: { merchantNo: string; storeNo?: string; reason: string }):
    Promise<{ storeCode: string }>;

  /**
   * 导出用：列表那几列 + <b>可直接印的码图</b>。
   *
   * 单开一条而不是给列表加开关：取码图会消耗微信永久码额度，
   * 而列表每翻一页都要调 —— 混在一起迟早有人给列表传上 true。
   */
  exportStoreQrcodes(q?: QrcodeQ): Promise<{ row: StoreQrcode; imageBase64: string | null }[]>;

  /**
   * 登记一次店铺码印刷量（线下事实，系统无从自动知道）。
   *
   * @param qty **有符号**：印多了冲减传负数 —— 补一行而不是改历史行，
   *   否则「当初到底印了多少」被抹掉，而那正是成本对账要问的。传 0 会被后端拒
   */
  recordQrcodePrint(v: { merchantNo: string; storeNo?: string; qty: number; size?: string; remark?: string }): Promise<void>;
  /** 门店获客效果（P-10.1.4）。 */
  /**
   * 获客漏斗「扫码 → 进店 → 首次归因 → 首单」，按**主体**聚合（P-10.1.4）。
   *
   * 粒度是主体不是门店：店铺码一主体一码，物理上分不出扫的是哪家分店。
   * 不传 `from`/`to` 时后端取最近 30 天 —— 不给「有史以来」那个数。
   */
  listStoreAcquisition(q?: AcquisitionQ): Promise<Page<StoreAcquisition>>;

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
