// 社区与网点域（矩阵 P-2.1 社区网格 / P-2.2 自提点）。
// 自提点模型的两类划分来自 ADR-005 —— 这是本域最重要的一条：STORE 与 NEIGHBOR
// 的报酬、脱敏、作用域规则完全不同，混成一种类型后面每条规则都要写 if。
import type { Archivable } from "./common";

export interface Community extends Archivable {
  /** 社区单号。平台端数据域裁剪的主键之一 */
  communityNo: string;
  /** 社区名（小区名） */
  name: string;
  /** 所属城市 */
  city: string;
  /** 网格：城市与社区之间的运营划分单位 */
  grid: string;
  /** 开城开关（P-2.1.2）：关掉后 C 端不再展示该社区，已有订单不受影响 */
  opened: boolean;
  /** 覆盖围栏半径，米（P-2.1.3） */
  fenceRadius: number;
  /** 本社区的自提点数量（列表直接给，避免逐行再查一次） */
  pickupCount: number;
  /** 建档时间 */
  createdAt: string;
}

/**
 * 自提点类型（ADR-005）：
 * - STORE    常驻点：入驻商家承接，**收履约服务费**，承接本点全部订单（含别家商家的商品）
 * - NEIGHBOR 临时点：团发起人家里，**零报酬**，作用域只有单个 group_no，脱敏要求更严
 */
export type PickupType = "STORE" | "NEIGHBOR";

/** MIGRATING = 迁移中（P-2.2.2）：不再接新单，存量单仍在本点核销完。 */
export type PickupStatus = "ACTIVE" | "SUSPENDED" | "MIGRATING";

export const PICKUP_TRANSITIONS: Record<PickupStatus, PickupStatus[]> = {
  ACTIVE: ["SUSPENDED", "MIGRATING"],
  SUSPENDED: ["ACTIVE"],
  // 迁移完成后只能停用（旧点不再启用），新点是另一条记录
  MIGRATING: ["SUSPENDED"],
};

export interface PickupPoint extends Archivable {
  /** 自提点单号 */
  pickupNo: string;
  /** 自提点名称 */
  name: string;
  /** 自提点类型。**STORE 与 NEIGHBOR 的报酬、脱敏、作用域规则完全不同**（ADR-005） */
  type: PickupType;
  /** 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完 */
  status: PickupStatus;
  /** 归属社区 */
  communityNo: string;
  /** 社区名快照 */
  communityName: string;
  /** 承接商家；NEIGHBOR 点为空（承接方是 C 端用户，不是商家） */
  merchantNo?: string;
  /** 承接商家名快照；NEIGHBOR 点为空 */
  merchantName?: string;
  /** 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 */
  address: string;
  /** 营业/可取货时段，形如 "09:00-21:00" */
  openHours: string;
  /** 到货时间（运营排车依据） */
  arriveTime: string;
  /**
   * 履约服务费费率，万分比（P-2.2.4）。**仅 STORE 有意义**，NEIGHBOR 恒为 0。
   * 存费率不存金额：R15 口径（按单/按件/保底）未定，等定了只改结算不改主数据。
   */
  serviceFeeRate: number;
  /** 近 30 天承接次数（P-2.2.5 职业化风控依据） */
  acceptCount30d: number;
  /** 建档时间 */
  createdAt: string;
}
