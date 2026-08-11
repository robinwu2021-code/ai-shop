// 社区与网点域（矩阵 P-2.1 社区网格 / P-2.2 自提点）。
// 自提点模型的两类划分来自 ADR-005 —— 这是本域最重要的一条：STORE 与 NEIGHBOR
// 的报酬、脱敏、作用域规则完全不同，混成一种类型后面每条规则都要写 if。
import type { Archivable } from "./common";

/**
 * 提报单的状态。
 *
 * 与覆盖项的 PENDING **同名不同物**：那个说「这一条覆盖算不算数」，
 * 这个说「这张提报单走到哪了」—— `APPROVED` 意味着平台已经建出了社区。
 */
export type CommunityApplyStatus = "PENDING" | "APPROVED" | "REJECTED";

/**
 * 商家提报的新社区（ADR-013 阶段三）。
 *
 * **它不是社区**：审过之后平台才建出来，`communityNo` 这时才有值。
 * 待审的小区不在任何选点列表里 —— 进了主表就会出现在用户面前，而点进去什么都没有。
 */
export interface CommunityApply {
  applyNo: string;
  /** 提报的商家 */
  merchantNo: string;
  /** 商家名。运营看着一串 M20260811… 判断不了任何事 */
  merchantName: string;
  /** 小区名，商家填 */
  name: string;
  /** 地址。运营靠它判断这是不是已有社区的另一个叫法 —— 同一个小区两条记录，商家会分不清该勾哪个 */
  address?: string;
  /** 商家选的区划，**只是建议**：最终以裁决时填的为准 */
  regionCode?: string;
  /** 区划整条路径名。「北山街道」全国有好几个，光末级判断不了是不是同一个地方 */
  regionPath?: string;
  /** 商家的补充说明：为什么要开这个点 */
  note?: string;
  status: CommunityApplyStatus;
  /** 通过后建出来的社区号；待审与驳回时为空 */
  communityNo?: string;
  /** 驳回原因。**原样出现在商家 B 端**，所以驳回必须填 */
  reason?: string;
  /** 提报时间 */
  submittedAt: number;
}

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
  /**
   * 所属行政区划码（`sys_region.region_code`），空 = 尚未归属。
   *
   * 挂上之后「按区/按街道覆盖」才能命中这个社区（ADR-013）。
   * **空着不代表配错了** —— 平台不按名字猜归属：猜错不报错，只会让这个社区
   * 悄悄出现在别人的经营范围里。
   */
  regionCode?: string;
  /**
   * 从省到自身的中文路径，如「浙江省 / 杭州市 / 西湖区 / 北山街道」。
   *
   * **后端拼好给的**：只给一个 330106002 的话，端上要么显示一串数字，
   * 要么自己按码长切片再逐级查 —— 而国标编码规则不是端该知道的事。
   */
  regionPath?: string;
}

/** 行政区划节点（ADR-013）。四级：省 / 市 / 区县 / 街道。 */
export interface Region {
  /** 统计用区划代码：省 2 位 / 市 4 位 / 区县 6 位 / 街道 9 位 */
  regionCode: string;
  parentCode?: string;
  /** PROVINCE / CITY / DISTRICT / STREET */
  level: string;
  name: string;
  /** 开城开关：停用只影响新的选择，存量商家不动 */
  enabled: boolean;
  /** 下面还有没有下级。**据此决定还要不要再选一层**，而不是点进去才发现是空的 */
  hasChild: boolean;
}

/**
 * 自提点类型（ADR-005）：
 * - STORE    常驻点：入驻商家承接，**收履约服务费**，承接本点全部订单（含别家商家的商品）
 * - NEIGHBOR 临时点：团发起人家里，**零报酬**，作用域只有单个 group_no，脱敏要求更严
 */
/**
 * 自提点类型。**与 shared 的 `PickupPointType` 同名同值** ——
 * 此前这里叫 `PickupType`，是同一个概念的第二个名字。
 * 一个领域概念只能有一个词（见 docs/requirements/项目词典.md）。
 */
export type PickupPointType = "STORE" | "NEIGHBOR" | "PLATFORM";

/**
 * 计费口径。**目前只有 PLATFORM 点有值** —— STORE 与 NEIGHBOR 恒为 NONE。
 *
 * ⚠️ 这与需求矩阵里「常驻点收履约服务费」看起来矛盾，其实是同一件未决事情的两个阶段：
 * **B9（履约服务费口径：按单/按件/保底）在待完成清单里明确标着「未定」**，
 * 所以 STORE 的费率还没开启；PLATFORM 点是线下逐点协商的，先落了字段。
 * B9 定了之后，STORE 才会有 PER_ITEM 或 RATE。
 */
export type PickupFeeMode = "NONE" | "PER_ITEM" | "RATE";

/** MIGRATING = 迁移中（P-2.2.2）：不再接新单，存量单仍在本点核销完。 */
export type PickupStatus = "ACTIVE" | "SUSPENDED" | "MIGRATING";

export const PICKUP_TRANSITIONS: Record<PickupStatus, PickupStatus[]> = {
  ACTIVE: ["SUSPENDED", "MIGRATING"],
  SUSPENDED: ["ACTIVE"],
  // 迁移完成后只能停用（旧点不再启用），新点是另一条记录
  MIGRATING: ["SUSPENDED"],
};

/** 新建自提点的入参。三类的必填项完全不同 —— 见各字段说明 */
export interface PickupDraft {
  /** 归属社区。必须真的存在，否则这个点对谁都不可见而列表看着正常 */
  communityNo: string;
  /** 自提点名称 */
  name: string;
  /** 三类的报酬与归属规则完全不同 */
  type: PickupPointType;
  /**
   * 承接方。**多态**：STORE 传门店号、NEIGHBOR 传用户号、PLATFORM 传空。
   * 传错会让「这个点属于谁」永久错位，而它决定核销权限与出货门店。
   */
  ownerRef?: string;
  /** 自提点地址 */
  address: string;
  /** 可取货时段 */
  openHours?: string;
  /** 到货说明（运营排车依据） */
  arrivalDesc?: string;
  /** 履约服务费费率，万分比。**NEIGHBOR 必须为 0** */
  serviceFeeRate?: number;
  /** 按件履约服务费（分）。**NEIGHBOR 必须为 0** */
  serviceFeePerItemMinor?: number;
}

export interface PickupPoint extends Archivable {
  /** 自提点单号 */
  pickupNo: string;
  /** 自提点名称 */
  name: string;
  /**
   * 自提点类型（ADR-005）。三类的报酬、脱敏、作用域规则完全不同。
   *
   * ⚠️ 这里此前只有 STORE|NEIGHBOR 两类，而后端还有 **PLATFORM**（平台提供、
   * 线下协商费率）—— 少一类的后果是平台点在列表里渲染成 undefined 或被当成常驻点，
   * 而它的费率规则与常驻点完全不同。
   */
  type: PickupPointType;
  /** 计费口径。目前只有 PLATFORM 有值，见 `PickupFeeMode` 的说明 */
  feeMode: PickupFeeMode;
  /** 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完 */
  status: PickupStatus;
  /** 归属社区 */
  communityNo: string;
  /** 社区名快照 */
  communityName: string;
  /**
   * 承接**门店**；NEIGHBOR 点为空（承接方是 C 端用户，不是商家）。
   *
   * 此前叫 `merchantNo` 且装的是主体号。自提点归属改到门店之后（后端 V16），
   * 名字与内容就对不上了 —— 一并改名，而不是让下一个人以为它还是主体号。
   */
  storeNo?: string;
  /** 承接商家名快照；NEIGHBOR 点为空。名字仍挂在主体上，不是门店名 */
  merchantName?: string;
  /** 自提点地址。NEIGHBOR 点**成团前只到楼栋**，付款后才给完整门牌 */
  address: string;
  /** 营业/可取货时段，形如 "09:00-21:00" */
  openHours: string;
  /** 到货时间（运营排车依据） */
  arriveTime: string;
  /**
   * 履约服务费费率，万分比（P-2.2.4）。**NEIGHBOR 恒为 0**（库上有 CHECK 约束兜底）。
   *
   * 目前有值的只有 PLATFORM 点（线下逐点协商）；STORE 要等 B9 定口径。
   * 存费率不存金额：口径（按单/按件/保底）未定，等定了只改结算不改主数据。
   */
  serviceFeeRate: number;
  /** 按件履约服务费（分）。与 serviceFeeRate 二选一，由 feeMode 决定用哪个 */
  serviceFeePerItemMinor: number;
  /** 近 30 天承接次数（P-2.2.5 职业化风控依据） */
  acceptCount30d: number;
  /** 建档时间 */
  createdAt: string;
}
