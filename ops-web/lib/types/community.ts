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
  /** 提报单业务键。裁决按它定位，**不用自增 id** —— 那个不对外，重建库就变 */
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
  /** ESTATE 小区 / VILLAGE 村。裁决的人要一眼看出这是哪种聚落 */
  kind?: SettlementKind;
  /** 关联的官方村码；非空 = 从词典选的，重复开通会被后端拦 */
  originCode?: string;
  /**
   * 带没带定位。**没带的要显眼** —— 通过后聚落没有坐标，
   * 买家用定位永远找不到它，运营得先补坐标再通过。
   */
  located?: boolean;
  /**
   * 商家提报时带的坐标（gcj02，E6）。**要看得见具体值** ——
   * 只给一个「有/无」，落点偏到隔壁区也照样显示「有定位」，判不出对错。
   */
  latE6?: number | null;
  /** 经度 ×1e6（gcj02） */
  lngE6?: number | null;
  /**
   * 官方村码在区划表里的坐标（高德批量补录）。没带定位时后端通过这条提报会自动用它兜底 ——
   * 两个都空，才是真的「通过后无坐标、买家搜不到」。
   */
  fallbackLatE6?: number | null;
  /** 兜底经度：商家没选点时用提交那一刻的位置。**多半不在那个小区里**，裁决要留意 */
  fallbackLngE6?: number | null;
  /** 待审 / 已建社区 / 已驳回。**只有 PENDING 能裁**：裁完就是终态，再裁一次意味着同一条提报有两个结论 */
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
  /** 所属聚落（楼栋 → 小区/园区）。空 = 顶层。列表要能看出谁在谁里面 */
  parentNo?: string | null;
  /** ESTATE / VILLAGE / BUILDING */
  kind?: string | null;
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
  /**
   * 聚落中心坐标（gcj02，×1e6）。**围栏那一屏要用** ——
   * 没标点的聚落算不出任何圈，而「算不出」与「圈里没人」在界面上长得一样，
   * 必须分开说：前者是待办，后者是事实。
   */
  latE6?: number | null;
  lngE6?: number | null;
}

/** 行政区划节点（ADR-013）。四级：省 / 市 / 区县 / 街道。 */
/**
 * 区划推断的一条候选。`source` 说明依据：ADDRESS 从地址文本推的、COORDS 按提报坐标找最近的村推的。
 * **两条线索都给出来让运营挑** —— 地址可能写错，坐标可能是商家在别处点的，互相印证比只信一个稳。
 */
/** 附近已开通的聚落。裁决查重用：名字不同、位置只差 50 米的两条，靠文字比对看不出来 */
export interface NearbyCommunity {
  /** 社区号 */
  communityNo: string;
  /** 名称 */
  name: string;
  /** 纬度 ×1e6（gcj02） */
  latE6: number;
  /** 经度 ×1e6（gcj02） */
  lngE6: number;
  /** 距提报坐标的直线距离（米） */
  distanceM: number;
  /** 「广东省 / 深圳市 / 龙华区 / 福城街道」 */
  regionPath: string;
}

/**
 * 疑似重复的一对聚落。
 *
 * `reason` 是**判据不是结论**：SAME_NAME 归一名相同、NEARBY 坐标很近且名字相似。
 * 两条都可能是误报（同一条街道里真有「一期」「二期」两个小区），
 * 所以界面上给的是「合并」按钮而不是自动合并 —— 合并会改一批商家的可见范围，
 * 错了要一条条捞回来。
 */
export interface CommunityDuplicate {
  /** 疑似重复的一方 */
  left: Community;
  /** 另一方 */
  right: Community;
  /** 原因 */
  reason: DuplicateReason;
  /** 两点直线距离（米）。有一方没坐标时为空 */
  distanceM?: number | null;
}

export interface RegionSuggestion {
  /** 国标区划码 */
  regionCode: string;
  /** 层级 */
  level: string;
  /** 名称 */
  name: string;
  /** 「广东省 / 深圳市 / 龙华区 / 福城街道」 */
  path: string;
  /** 来源 */
  source: RegionMatchSource;
  /** 依据：匹配到的地址片段，或「茜坑社区 · 320 米」 */
  detail: string;
}

export interface Region {
  /** 统计用区划代码：省 2 位 / 市 4 位 / 区县 6 位 / 街道 9 位 */
  regionCode: string;
  /** 上级区划码。省级为空 —— 逐级选择器据此判断自己是不是在顶层 */
  parentCode?: string;
  /** PROVINCE / CITY / DISTRICT / STREET / VILLAGE（村委会·居委会，第五级） */
  level: string;
  /** 本级名称，**不含上级**（「西湖区」不是「杭州市 / 西湖区」）。要整条路径的地方自己拼，见 CommunityApply.regionPath */
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
/** PENDING/REJECTED（P1）：商家自建点的审核态 —— 先审后用，地址要印在买家取货页上 */
export type PickupStatus = "ACTIVE" | "SUSPENDED" | "MIGRATING" | "PENDING" | "REJECTED";

export const PICKUP_TRANSITIONS: Record<PickupStatus, PickupStatus[]> = {
  // 审核态不走启停迁移：裁决端点单独处理（decidePickup）
  PENDING: [],
  REJECTED: [],
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
  /** 自提点状态。`MIGRATING` = 不再接新单，存量单仍在本点核销完；`PENDING` = 商家自建待核实 */
  status: PickupStatus;
  /** 坐标（E6）。审自建点时要看：没坐标的点买家用定位找不到 */
  latE6?: number | null;
  /** 经度 ×1e6（gcj02） */
  lngE6?: number | null;
  /** 驳回理由，只有 REJECTED 有值 */
  rejectReason?: string | null;
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


// ── 2026-08-30：从 interface 里提出来的具名类型（内联联合对工具不可见）──

/** 聚落类型。裁决的人要一眼看出这是小区还是村 —— 两者的重复判据不一样 */
export type SettlementKind = "ESTATE" | "VILLAGE";

/** 判重的依据。SAME_NAME 同名 / NEARBY 坐标相近 —— 运营据此决定合不合 */
export type DuplicateReason = "SAME_NAME" | "NEARBY";

/** 行政区划是怎么定出来的。ADDRESS 靠地址串匹配 / COORDS 靠坐标反查 */
export type RegionMatchSource = "ADDRESS" | "COORDS";

/**
 * 坐标健康度 —— **整个位置模块的分母**。
 *
 * 门店没标点时后端那条自送半径的闸**直接放行**（缺数据不该拦正常订单，这是对的）。
 * 代价是商家以为自己限了三公里、实际多远的单都进来，等他要送货才发现送不到，
 * 那时钱已经收了。而这件事此前在任何界面上都看不见 —— 商家看不见，运营也看不见。
 */
export interface CoverageHealth {
  stores: {
    total: number;
    withCoords: number;
    /** 没标点的那些。**给明细不只给数字** —— 只给一个数，运营下一步无从做起 */
    missing: {
      storeNo: string;
      storeName: string;
      /** 从这里跳到商家去催他标点。**刻意不带商家名**（取名字要绕数据域） */
      merchantNo: string;
      /** 他以为自己限了多少米，而实际一米都没限 —— 后果有多大就看这个数 */
      deliveryRadiusM: number | null;
    }[];
  };
  /** 地址**只给聚合数**：那是个人信息，看总数就够判断分母有多脏 */
  addresses: { total: number; withCoords: number };
  communities: {
    total: number;
    withCoords: number;
    /** 没坐标的聚落**谁也匹配不到** —— 而它看起来一切正常：建档成功、列表里有 */
    missing: { communityNo: string; name: string }[];
  };
}

/**
 * 围栏改动的影响预览。
 *
 * 只给「当前半径」没用 —— 运营要回答的是「改成 1500 会多进来几户」，
 * 而这件事此前在任何界面上都算不出来，只能改完再等有人投诉。
 */
export interface FenceImpact {
  currentRadiusM: number;
  previewRadiusM: number;
  currentInside: number;
  previewInside: number;
  /** 有坐标的收货地址总数。**分母要给** —— 「多进来 0 户」在一个没几条地址有坐标的库里说明不了任何事 */
  addressesWithCoords: number;
}

/**
 * 位置分布：聚落 × 买家 × 商家 × 商品。
 *
 * **最要紧的不是那几行，是 `unattributable`。** 把算不了的静默丢掉，
 * 这张表就会把「缺数据」说成「缺需求」—— 而运营会据此去撤一个其实有人的片区的商家。
 * 分母写错的分析比没有分析更危险：没有分析时人会去查，有一张看起来完整的表时，人会直接照着做。
 */
export interface CoverageDistribution {
  rows: DistributionRow[];
  unattributable: Unattributable;
}

export interface DistributionRow {
  communityNo: string;
  name: string;
  /** ESTATE / VILLAGE / BUILDING */
  kind: string;
  regionPath?: string | null;
  /** 围栏内有坐标的收货地址数 */
  buyerCount: number;
  /** 社区池里在这儿有货的主体数 —— 是「买家真搜得到」，不是「谁框了这儿」 */
  merchantCount: number;
  goodsCount: number;
}

export interface Unattributable {
  /** 没坐标的收货地址：推不出聚落，**不是没人** */
  addressesWithoutCoords: number;
  /** 有坐标但不落在任何围栏里：那儿真的有人，只是还没在那儿开聚落 —— **这是开城线索** */
  addressesOutsideFences: number;
  /** 没标点的门店：自送半径对它形同虚设 */
  storesWithoutCoords: number;
  /** 已关闭的聚落：不在 rows 里，但历史数据还在 */
  communitiesClosed: number;
}
