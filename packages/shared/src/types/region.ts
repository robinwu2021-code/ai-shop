// 行政区划 · 社区 · 服务范围（「货能送到哪」的地理侧）
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

import type {
  AREA_LEVEL,
  AREA_STATUS,
  COMMUNITY_APPLY_STATUS,
} from "@shared/utils/constants";
import type { Pickup } from "./fulfillment";

/** 逆地理编码结果（P2）：recommend 是带楼盘/门牌的人话版 */
export interface GeoReverseResult {
  /** 带楼盘/门牌的人话版地址。**端上填这个** */
  recommend: string;
  /** 地址 */
  address: string;
}
/**
 * 地点输入提示（高德 inputtips 经后端代理）。提报小区时按名搜 POI，选中就带上坐标 ——
 * 否则坐标只能是「提交那一刻商家站的地方」，多半不在那个小区里。
 * 后端没配 Web 服务 key 时返回空数组，端上就当没有这个功能。
 */
export interface GeoTip {
  /** 名称 */
  name: string;
  /** 地址 */
  address?: string | null;
  /** 国标 6 位区县码，与 sys_region 同口径 */
  adcode?: string | null;
  /** 有些提示（纯地名、公交线）没坐标，这种不值得选 */
  latE6?: number | null;
  /** 经度 ×1e6。**全站坐标一律 gcj02** */
  lngE6?: number | null;
  /** 高德 POI 类目码 */
  typecode?: string | null;
}
/** 跨级搜索（P1）：区划命中带从省到父级的路径，聚落命中带所在街道路径 */
export interface RegionSearchResult {
  /** 命中的行政区划，带从省到父级的路径 */
  regions: Array<{ regionCode: string; level: string; name: string; path: string }>;
  /** 命中的社区 */
  communities: Array<{
    communityNo: string; name: string; regionCode?: string | null; path: string;
    /** ESTATE 小区 / VILLAGE 村。判「这一条底下还有没有下一级」用它，名字这时已经是口语名了 */
    kind?: string | null;
    /**
     * 所属聚落（楼栋 → 小区/园区）。为空 = 顶层聚落。
     * 少了它，搜出来的楼栋在整个小区已被勾中时仍显示成「没选上」，商家会再勾一遍。
     */
    parentNo?: string | null;
    /** 下钻要用它，不是 regionCode（那是它挂的街道/镇）。没有它就是地图开通的小区，没有下一级 */
    originCode?: string | null;
    /** 原始官方名（如「景滑村委会」），仅供展示/追溯 —— 判城乡用下面的 rural */
    originName?: string | null;
    /** 是不是村委会（服务端存的）。判「这一条给不给 ›」用它，不要解析 originName */
    rural?: boolean;
    latE6?: number | null;
    lngE6?: number | null;
  }>;
  /**
   * 还没开通的**官方村**（第五级名录）。已开通的那些走 `communities`（能直接勾），
   * 这里只出没开通的 —— 同一个地方不该在两组里各出现一次。
   * 官方村提报即开通，所以端上点一条就能直接用。
   */
  villages?: Array<{
    regionCode: string;
    name: string;
    /** 它挂的街道码（9 位）。提报要挂到这下面 */
    streetCode: string;
    path: string;
    latE6?: number | null;
    lngE6?: number | null;
    /** 是不是村委会（服务端存的）。判「这一条给不给 ›」用它 */
    rural?: boolean;
  }>;
  /**
   * 地图上的地点（v5）。**只在库里没有村/小区命中时才有值** —— 服务端先查库，
   * 库里没有才现问高德；App 不用再自己调原生 SDK 兜底了。
   */
  places?: GeoTip[];
}
export type AreaLevel = (typeof AREA_LEVEL)[keyof typeof AREA_LEVEL];
export type AreaStatus = (typeof AREA_STATUS)[keyof typeof AREA_STATUS];
export type CommunityApplyStatus =
  (typeof COMMUNITY_APPLY_STATUS)[keyof typeof COMMUNITY_APPLY_STATUS];
/**
 * 行政区划树上的一个节点（`/mp/regions`）。
 *
 * 与 {@link RegionOption} 是**两个问题的答案**，不要混用：
 * `RegionOption` 答的是「我能在哪儿取货」（只列有已开通社区的区），
 * 这个答的是「我家在哪儿」—— 没开通的区也要能选出来，人确实住在那儿。
 */
export interface RegionNode {
  /** 国标码：省 2 位 / 市 4 位 / 区县 6 位 */
  regionCode: string;
  /** 上级码。省级为 null */
  parentCode?: string | null;
  /** `PROVINCE` | `CITY` | `DISTRICT`。地址簿只到区县，街道与村不下发 */
  level: string;
  /** 名称 */
  name: string;
  /**
   * 还有没有下一级。**区县恒为 false** —— 地址表只有省市区三列，
   * 让人点进街道再挑一个存不下去的东西，比不让他挑更糟。
   */
  hasChild: boolean;
}
export interface RegionOption {
  /** 区县级国标码（6 位）。社区可能挂在街道级，聚合时截到区县 */
  regionCode: string;
  /** 区县名，如「西湖区」 */
  name: string;
  /** 所属市码（4 位） */
  cityCode: string;
  /** 所属市名。同名区县全国很多（如「城关区」），不带市名用户分不清是哪一个 */
  cityName: string;
  /** 该区县下已开通的社区数。「西湖区 · 2 个小区」比光秃秃一个区名有用得多 */
  communityCount: number;
}
export interface Community {
  /** 社区单号 */
  communityNo: string;
  /** 社区名（小区名） */
  name: string;
  /** 社区地址 */
  address: string;
  /** 所属城市。全市范围的商家靠它判定可达 */
  cityCode: string;
  /**
   * 所属街道/镇（9 位区划码）。商家框范围时「按街道看聚落」靠它 ——
   * 不下发的话端上只能拿到一锅平铺清单，街道视图无从分组。
   */
  regionCode?: string;
  /**
   * `ESTATE` 小区 / `VILLAGE` 村 / `BUILDING` 楼栋（写字楼）。
   *
   * **不再只是展示标签**：`BUILDING` 这一档参与匹配 —— 定位到最内层聚落时
   * 「层级优先于距离」，站在楼门口时隔壁小区的中心可能比本楼中心更近，
   * 按距离取会把「我在 3 幢」判成「我在隔壁小区」，而两者的商品池不同。
   */
  kind?: string;
  /**
   * 所属聚落（楼栋 → 小区/园区）。**为空 = 顶层聚落**，直接挂 `regionCode`。
   *
   * 只做两层：园区 › 楼 › 单元 › 户会没完没了，而单元和户不是服务单位 ——
   * 没有商家按单元框范围，它们属于收货地址的门牌号。
   */
  parentNo?: string | null;
  /** 米 */
  distance: number;
  /** 本社区可用的自提点 */
  pickups: Pickup[];
  /**
   * 官方村码，只有 `kind=VILLAGE` 且经官方名录开通的才有。**`regionCode` 是它挂的
   * 街道/镇，不是它自己** —— 经营范围选择器再往下钻一层要用这个码，不能用 regionCode，
   * 否则「牛杜村」会被当成「牛杜镇」去下钻。
   */
  originCode?: string | null;
  /**
   * `originCode` 对应的原始官方名（「景滑村委会」，未清理）——仅供展示/追溯，
   * 判「是不是村委会」不要解析它，用下面的 `rural` 字段（服务端存的，不是端上猜的）。
   */
  originName?: string | null;
  /**
   * 是不是村委会（`sys_region.rural`，经 origin_code 反查）。只对 kind=VILLAGE 有意义：
   * 村委会到此为止、不再下钻；居委会/社区还能再挑具体小区。
   */
  rural?: boolean;
  /** 官方村名录批量补录过的坐标，可能为空 */
  latE6?: number | null;
  /** 经度 ×1e6。**全站坐标一律 gcj02** */
  lngE6?: number | null;
}
/**
 * 一个坐标解析出来的位置上下文（`/mp/location/resolve`）。
 *
 * **不要用 `nearbyCommunities` 的第一条代替它**：「最内层」的判据是
 * 层级优先于距离 —— 站在楼门口时，隔壁小区的中心可能比本楼中心更近。
 * 那是业务规则，放端上就会有三份实现，而它们迟早不一样。
 */
export interface LocationContext {
  /** 最内层聚落。**null 不是异常** —— 新城区一个围栏都没落进、或坐标是模糊的 */
  innermostNo: string | null;
  /** 顶栏直接显示它，省端上再查一次 */
  innermostName: string | null;
  /** 归属链，由内到外（含 innermost）。商品池按「链上任一命中」取并集 */
  chainNos: string[];
  /** 原样回传：坐标是不是模糊定位给的。端上据此决定要不要显示距离 */
  coarse: boolean;
}

/** 一条地理覆盖项。名字由后端拼好下发 —— 端上只拿到 330106 的话，要么显示一串数字，要么自己再查一次 */
export interface ServiceArea {
  /** 粒度：社区 / 村 / 街道 / 区县 / 城市。**可跨粒度组合** —— 三个小区 + 一个区是四条 */
  level: AreaLevel;
  /** level=COMMUNITY 时是社区号，否则是区划码 */
  refCode: string;
  /** 展示名。区级以上是「浙江省 / 杭州市 / 西湖区」整条路径 —— 光一个「西湖区」全国有好几个，商家分不出删哪条 */
  name: string;
  /** 业务键（服务端回填）。范围子集（P2）按它引用；端上新加的项没有，保存后才有 */
  areaNo?: string;
  /**
   * 覆盖方向：`INCLUDE` 纳入（默认，不传即此）/ `EXCLUDE` 排除。
   *
   * 它回答的是「商家框了小区，算不算覆盖里面每栋楼」—— **默认算**，
   * 但给一个显式的出口：勾了整个小区、单独排除 3 幢。
   * 展开时先并后减，EXCLUDE 优先；而矛盾应当在**输入端**消除
   * （勾了排除就把对应的 include 去掉），不要求用户记住这条优先级。
   */
  mode?: "INCLUDE" | "EXCLUDE";
  /**
   * `ACTIVE` 已生效 / `PENDING` 待运营审核。
   *
   * 勾已有社区自助生效；勾区、街道要审 —— 一家菜摊声称覆盖整个西湖区，
   * 影响面差一个量级（ADR-013 §4.2）。**端上必须把待审标出来**：
   * 待审的不参与展开，商家看着它在清单里却一个订单也不来，
   * 而这是他自己永远查不出来的那类故障。
   */
  status?: AreaStatus;
}
/**
 * 商家提报的新社区（ADR-013 阶段三）。
 *
 * 提报**不等于**社区已存在：审过之后平台才建出来，`communityNo` 这时才有值。
 * 端上别拿它去当社区用 —— 待审的小区不在任何选点列表里。
 */
export interface CommunityApply {
  /** 提报单业务键 */
  applyNo: string;
  /** 提报的商家 */
  merchantNo: string;
  /** 商家名。运营看着一串 M20260811… 判断不了任何事 */
  merchantName: string;
  /** 小区名，商家填 */
  name: string;
  /** 地址。运营靠它判断这是不是已有社区的另一个叫法 —— 批重了商家会分不清该勾哪个 */
  address?: string;
  /** 商家选的区划，**只是建议** —— 最终以运营裁决时填的为准 */
  regionCode?: string;
  /** 区划整条路径名。「北山街道」全国有好几个，光末级判断不了是不是同一个地方 */
  regionPath?: string;
  /** 补充说明：为什么要开这个点 */
  note?: string;
  /** 待审 / 已建社区 / 已驳回。裁完即终态 */
  status: CommunityApplyStatus;
  /** 通过后建出来的社区号；待审与驳回时为空 */
  communityNo?: string;
  /** 驳回原因，**原样展示给商家** —— 不给理由他只会原样再提一次 */
  reason?: string;
  /** 提报时间（毫秒时间戳）*/
  submittedAt: number;
}
export interface CommunityApplyReq {
  /** 小区名。**必填** —— 运营要靠它与地址一起判断是不是已有社区的另一个叫法 */
  name: string;
  /** 地址。不填也能提，但运营多半会驳回：光一个小区名判断不了是不是重复 */
  address?: string;
  /** 商家选的区划，只是建议。留空由运营裁决时补 */
  regionCode?: string;
  /** 补充说明：为什么要开这个点 */
  note?: string;
  /** ESTATE 小区 / VILLAGE 村。不传按 ESTATE —— 聚落模型下两者同一条链路 */
  kind?: string;
  /** 提报村时从词典（/biz/regions/villages）选中的官方村码。查重与溯源用 */
  originCode?: string;
  /**
   * 提报时的定位。**尽量带上** —— 通过后聚落的坐标就是它，
   * 没有坐标的聚落买家用定位永远找不到。拿不到权限时可空，不阻塞提报。
   */
  latE6?: number;
  /** 经度 ×1e6。**全站坐标一律 gcj02** */
  lngE6?: number;
}
/** 行政区划的一级（`/biz/regions`）。省 2 / 市 4 / 区 6 / 街道 9 / 村 12 位 */
export interface Region {
  /** 统计用区划代码：省 2 / 市 4 / 区县 6 / 街道 9 / 村 12 位。**前缀即层级**，下级码以上级码开头。
   *  商家补录的村是 `街道码 + M + 2 位`，字母保证与官方纯数字码永不冲突 */
  regionCode: string;
  /** 上级区划码。省级为空 —— 逐级选择器据此判断自己在不在顶层 */
  parentCode?: string;
  /** PROVINCE / CITY / DISTRICT / STREET / VILLAGE（村委会·居委会，第五级） */
  level: string;
  /**
   * 中心点（gcj02，E6）。**可能为空** —— 全国 62 万条村级里只有批量补录命中的那部分有坐标，
   * 端上据此决定是直接用，还是临时去地图上搜一次。
   */
  latE6?: number | null;
  /** 经度 ×1e6。**全站坐标一律 gcj02** */
  lngE6?: number | null;
  /** 本级名称，**不含上级**（「西湖区」不是「杭州市 / 西湖区」）。要整条路径的地方自己拼 */
  name: string;
  /** 是否启用。B 端只会拿到启用的 —— 停用的区划是运营的维护对象，不该出现在商家的选择器里 */
  enabled: boolean;
  /** 下面还有没有下级。端上据此决定「还要不要再往下选一层」，而不是点进去才发现是空的 */
  hasChild: boolean;
  /** `OFFICIAL`（官方数据）/ `MERCHANT`（本店补录）。端上据此标出「我加的」 */
  source?: string;
  /**
   * 本店补录且运营还没确认 —— **只有自己看得见**。
   *
   * <p>要标出来：不标的话商家不知道这条还没共享，
   * 会以为别的店也能看到他加的这个村。
   */
  pending?: boolean;
  /** `PENDING` / `APPROVED` / `REJECTED`。官方数据恒为 APPROVED */
  auditStatus?: string;
  /**
   * 驳回理由。**要显示给商家** —— 看不到的话那个村在他那里凭空消失，
   * 他不知道为什么，多半原样再录一遍。
   */
  rejectReason?: string;
  /**
   * 只对 level=VILLAGE 有意义：是不是村委会（`sys_region.rural`，服务端存的，
   * 不是端上按名字猜的）。`true` = 到此为止，选择器不再往下钻（自然村数据地图上
   * 本来就搜不全）；`false` = 居委会/社区，或非第五级，底下还能再挑具体小区。
   */
  rural?: boolean;
}
