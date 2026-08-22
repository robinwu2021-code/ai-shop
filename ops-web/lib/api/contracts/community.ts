// 覆盖范围：社区网格（P-2.1）与自提点主数据（P-2.2）。
import type { Community, CommunityApply, Page, PickupPoint, PickupStatus, Region } from "@/lib/types";
import type { PickupDraft } from "@/lib/types";
import type { CommunityApplyQ, CommunityQ, PickupQ } from "../query";

export interface CommunityApi {
  listCommunities(q?: CommunityQ): Promise<Page<Community>>;
  /** 开城/停城（P-2.1.2）。停城不影响已有订单，只是 C 端不再展示。 */
  setCommunityOpen(communityNo: string, opened: boolean): Promise<Community>;
  /** 覆盖围栏半径，米（P-2.1.3）。 */
  setCommunityFence(communityNo: string, fenceRadius: number): Promise<Community>;
  /**
   * 把社区挂到行政区划下（ADR-013）。**建议挂到街道级** ——
   * 挂区县也能用，但那样「按街道覆盖」就退化成了「按区覆盖」。
   *
   * @param regionCode 传空表示清空归属
   */
  setCommunityRegion(communityNo: string, regionCode: string): Promise<Community>;

  // ── 商家提报的新社区（ADR-013 阶段三）──────────────────────────

  /**
   * 提报队列。默认只看待审 —— 这是个队列，历史是次要视图。
   *
   * 它补的是一条死路：商家开在平台还没开的小区里，覆盖项只能从已有社区里勾，
   * 而「让平台加一个小区」此前没有任何入口。
   */
  listCommunityApplies(q?: CommunityApplyQ): Promise<Page<CommunityApply>>;
  /**
   * 裁决。**通过就当场建出这个社区**，驳回必须写原因（原样回给商家）。
   *
   * @param regionCode 运营最终认定的区划，空则沿用商家填的。
   *                   不挂的话这个新社区在任何「按区覆盖」里都出不来
   */
  decideCommunityApply(applyNo: string, pass: boolean,
                       opts?: { regionCode?: string; reason?: string }): Promise<CommunityApply>;

  // ── 行政区划（ADR-013）─────────────────────────────────────────

  /**
   * 某区划的直接下级。`parent` 为空取省级。
   *
   * **逐级查，不给整棵树**：四级共 44703 行、1.6 MB。挑一个街道只需沿
   * 「省 → 市 → 区 → 街道」走四次、每次几十条；给整棵树的话每开一次页面
   * 都要传一遍全国，而其中 99.9% 用不到。
   */
  listRegions(parent?: string, enabledOnly?: boolean): Promise<Region[]>;

  /**
   * 区划人工维护（新增 / 停用 / 改名）。
   *
   * <p>官方数据停更（统计局 2024-10 起），真实发生的区划调整只能手工补。
   * enabled 此前上线两年没有任何写入口 ——「开城开关」从来没有开关。
   */
  createRegion(parent: string, name: string): Promise<Region>;
  /** 停用只影响新选择，存量商家的范围不动；不级联 */
  toggleRegion(code: string, enabled: boolean): Promise<Region>;
  /** 改名不动码，存量引用不受影响 */
  renameRegion(code: string, name: string): Promise<Region>;
  /** 从省到自身的整条链路。给选择器回显用 —— 端上不该自己按码长切片 */
  regionPath(code: string): Promise<Region[]>;
  archiveCommunity(communityNo: string): Promise<Community>;
  unarchiveCommunity(communityNo: string): Promise<Community>;

  listPickups(q?: PickupQ): Promise<Page<PickupPoint>>;
  /**
   * 建自提点。
   *
   * **此前全平台没有任何创建路径** —— 运营端只有列表/停启/费率，商家不能申请、
   * 邻居不能报名。社区自提是平台的核心履约方式，却无法录入一个点。
   *
   * `ownerRef` 是多态的：STORE 传门店号、NEIGHBOR 传用户号、PLATFORM 传空。
   */
  createPickup(draft: PickupDraft): Promise<PickupPoint>;
  /** 启停与迁移（P-2.2.2），非法迁移抛错。 */
  setPickupStatus(pickupNo: string, status: PickupStatus): Promise<PickupPoint>;
  /**
   * 裁决商家自建的自提点（P1）：PENDING → ACTIVE / REJECTED。
   * 驳回必须带理由 —— 它原样回给商家，不写他只会原样再提一次。
   */
  decidePickup(pickupNo: string, pass: boolean, reason?: string): Promise<PickupPoint>;
  /** 履约服务费费率，万分比（P-2.2.4）。⚠️ 仅 STORE 可配，NEIGHBOR 零报酬（ADR-005 §4）。 */
  setPickupServiceFee(pickupNo: string, serviceFeeRate: number): Promise<PickupPoint>;
  /** 疑似职业化的临时自提点（P-2.2.5）：近 30 天承接次数 ≥ 阈值。 */
  listRiskyNeighborPickups(q?: PickupQ): Promise<Page<PickupPoint>>;
  archivePickup(pickupNo: string): Promise<PickupPoint>;
  unarchivePickup(pickupNo: string): Promise<PickupPoint>;
}
