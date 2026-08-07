// 覆盖范围：社区网格（P-2.1）与自提点主数据（P-2.2）。
import type { Community, Page, PickupPoint, PickupStatus } from "@/lib/types";
import type { CommunityQ, PickupQ } from "../query";

export interface CommunityApi {
  listCommunities(q?: CommunityQ): Promise<Page<Community>>;
  /** 开城/停城（P-2.1.2）。停城不影响已有订单，只是 C 端不再展示。 */
  setCommunityOpen(communityNo: string, opened: boolean): Promise<Community>;
  /** 覆盖围栏半径，米（P-2.1.3）。 */
  setCommunityFence(communityNo: string, fenceRadius: number): Promise<Community>;
  archiveCommunity(communityNo: string): Promise<Community>;
  unarchiveCommunity(communityNo: string): Promise<Community>;

  listPickups(q?: PickupQ): Promise<Page<PickupPoint>>;
  /** 启停与迁移（P-2.2.2），非法迁移抛错。 */
  setPickupStatus(pickupNo: string, status: PickupStatus): Promise<PickupPoint>;
  /** 履约服务费费率，万分比（P-2.2.4）。⚠️ 仅 STORE 可配，NEIGHBOR 零报酬（ADR-005 §4）。 */
  setPickupServiceFee(pickupNo: string, serviceFeeRate: number): Promise<PickupPoint>;
  /** 疑似职业化的临时自提点（P-2.2.5）：近 30 天承接次数 ≥ 阈值。 */
  listRiskyNeighborPickups(q?: PickupQ): Promise<Page<PickupPoint>>;
  archivePickup(pickupNo: string): Promise<PickupPoint>;
  unarchivePickup(pickupNo: string): Promise<PickupPoint>;
}
