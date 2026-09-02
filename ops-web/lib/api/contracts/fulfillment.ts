// 覆盖范围：履约调度（P-5.1）。实际核销动作在 B 端核销台，这里只做调度与监控。
import type { ArrivalBatch, BatchStatus, CarrierConfig, FreightTemplate, OverdueRule, Page, RedeemStat, Shipment, SortingRow } from "@/lib/types";
import type { BatchQ, PageQ, ScopedQ } from "../query";

export type ShipmentQ = PageQ & { status?: string; carrier?: string };

export interface FulfillmentApi {
  listArrivalBatches(q?: BatchQ): Promise<Page<ArrivalBatch>>;
  /** 批次推进（计划→已发车→已到货→已签收），跳步抛错。 */
  setBatchStatus(batchNo: string, status: BatchStatus): Promise<ArrivalBatch>;
  /** 按自提点汇总分拣（P-5.1.2）。只返回**已签收**批次的货。 */
  listSorting(q?: ScopedQ): Promise<Page<SortingRow>>;
  /** 核销监控与逾期看板（P-5.1.3）。 */
  listRedeemStats(q?: ScopedQ): Promise<Page<RedeemStat>>;
  getOverdueRule(): Promise<OverdueRule>;
  /** 逾期规则（P-5.1.4）。宽限小时数 <1 抛错 —— 到点即作废必产生客诉。 */
  saveOverdueRule(rule: Pick<OverdueRule, "action" | "graceHours" | "maxPostpone">): Promise<OverdueRule>;

  // ── 快递与轨迹（P-5.2.1 / 5.2.2）──────────────────────────────

  listShipments(q?: ShipmentQ): Promise<Page<Shipment>>;

  /**
   * 换运单号（录错了、或承运商重新出单）。
   *
   * - 同一承运商下运单号**不能重复** —— 重复会把两单的轨迹搅在一起，之后谁也说不清哪条是哪单的；
   * - **已签收的不许改**：货都到了再改单号，等于把一条已完成的轨迹指向别处；
   * - `reason` 必填。
   */
  updateWaybill(v: { shipmentNo: string; waybillNo: string; reason: string }): Promise<Shipment>;

  // ── 运费模板与超区（P-5.2.3）──────────────────────────────────

  /** `showArchived` 为真时连归档的一起返回（G1：归档不是删除，得看得见）。 */
  listFreightTemplates(q?: { showArchived?: boolean }): Promise<Page<FreightTemplate>>;

  /**
   * 新建/保存运费模板（含超区规则）。
   *
   * 校验都在 mock 层：首重下限、续重单位为正、超区区域不重复、
   * **不配送的区域不能填加价额**（填了就是调用方理解错了，拒绝而不是忽略）。
   */
  saveFreightTemplate(v: Omit<FreightTemplate, "updatedAt" | "updatedBy" | "templateNo"> & { templateNo?: string }): Promise<FreightTemplate>;

  /**
   * 归档模板（G1：软删除，不是删除）。
   *
   * 硬删会把历史订单的运费依据一起抹掉 —— 之后谁也说不清那单当时为什么收了 8 元。
   * 默认模板归档不了：归档之后新商家没有模板可用。
   */
  archiveFreightTemplate(templateNo: string): Promise<FreightTemplate>;
  unarchiveFreightTemplate(templateNo: string): Promise<FreightTemplate>;

  // ── 第三方运力配置（P-5.2.4）──────────────────────────────────

  listCarriers(): Promise<CarrierConfig[]>;

  /**
   * 保存一家运力的接入配置。
   *
   * - 优先级不能与别家重复 —— 同优先级时选哪家取决于顺序，那是隐性行为；
   * - 截单时间必须是 `HH:mm`，时效必须为正；
   * - **密钥不在这里配**：契约里只有 `apiKeyConfigured` 这个布尔，
   *   密钥本身不该出现在前端契约里，哪怕是脱敏的。
   */
  saveCarrier(v: Pick<CarrierConfig, "carrier" | "name" | "priority" | "pickupCutoff" | "slaHours">): Promise<CarrierConfig>;

  /**
   * 启停一家运力。
   *
   * - **没配密钥的不能启用**：启用了下单当场失败；
   * - **还有在途快递单的不能停用**：停了之后那些单的轨迹拉不回来；
   * - **不能把最后一家启用的也停掉**：全停之后快递单无处可下。
   */
  setCarrierEnabled(carrier: string, enabled: boolean): Promise<CarrierConfig>;
}
