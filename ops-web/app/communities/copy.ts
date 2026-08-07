// 社区与网点文案（矩阵 P-2.1 / P-2.2）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {
  tabGrid: "社区网格",
  tabPickups: "自提点",
  tabNeighbor: "临时点监控",

  openedYes: "已开城",
  openedNo: "未开城",

  /** `{name}` 是社区名 */
  toastOpened: "{name} 已开城",
  toastClosed: "{name} 已停止开城",
  toastPickupStatus: "已更新自提点状态",
  toastFeeSaved: "已更新履约服务费费率",

  colCommunityNo: "社区编号",
  colCommunity: "社区",
  colCityGrid: "城市 / 网格",
  colOpened: "开城",
  ariaOpenSwitch: "{name} 开城开关",
  colRadius: "覆盖半径",
  colPickupCount: "自提点",
  colCreatedAt: "创建时间",
  colActions: "操作",
  archiveHintCommunity: "该社区下仍有自提点，请先迁移或停用",
  entityCommunity: "社区",
  entityPickup: "自提点",

  colPickupNo: "自提点编号",
  colName: "名称",
  colType: "类型",
  colCarrier: "承接商家",
  colTimes: "到货 / 营业",
  colFeeRate: "服务费费率",
  zeroFee: "零报酬",
  ariaFeeRate: "费率（万分比）",
  save: "存",
  cancel: "取消",
  colStatus: "状态",
  btnSuspend: "停用",
  btnActivate: "启用",
  btnFinishMigrate: "完成迁移",

  col30dAccept: "30 天承接",
  suspectPro: "疑似职业化",
  colFiledAt: "建档时间",
  btnPauseIntake: "暂停接单",

  readOnlyCommunityWhat: "社区开城与围栏配置",
  readOnlyCommunityNote: "不能开城、改半径或归档",
  readOnlyPickupWhat: "自提点建档与启停",
  readOnlyPickupNote: "不能启停、改费率或归档",

  /** `{n}` 是阈值次数 */
  neighborNotice:
    "近 30 天承接 ≥ {n} 次的邻里自提点（ADR-005 §F6 建议阈值）。频繁承接说明它已经不是「顺手帮邻居带一次」，而是在无证经营自提点 —— 需要人工复核后转为常驻点或暂停。",
  searchGrid: "搜索社区编号 / 名称 / 网格",
  searchPickup: "搜索自提点编号 / 名称 / 地址",
  filterOpened: "按开城状态筛选",
  filterOpenedAll: "全部开城状态",
  filterType: "按类型筛选",
  filterTypeAll: "全部类型",
  filterStatus: "按状态筛选",
  filterStatusAll: "全部状态",

  emptyGrid: "没有符合条件的社区。清空筛选，或先在城市网格里建一个社区。",
  emptyPickup: "没有符合条件的自提点。自提点是履约的落点，缺它货到了没人能核销。",
  emptyNeighbor: "当前没有触发阈值的临时自提点（近 30 天承接 ≥ {n} 次）。这是好事，不是数据缺失。",
};

const en: typeof zh = {
  tabGrid: "Community grids",
  tabPickups: "Pickup points",
  tabNeighbor: "Temporary points",

  openedYes: "Launched",
  openedNo: "Not launched",

  toastOpened: "{name} is now live",
  toastClosed: "{name} is no longer live",
  toastPickupStatus: "Pickup point status updated",
  toastFeeSaved: "Fulfillment service fee updated",

  colCommunityNo: "Community no.",
  colCommunity: "Community",
  colCityGrid: "City / grid",
  colOpened: "Launched",
  ariaOpenSwitch: "Launch toggle for {name}",
  colRadius: "Geofence radius",
  colPickupCount: "Pickup points",
  colCreatedAt: "Created at",
  colActions: "Actions",
  archiveHintCommunity: "This community still has pickup points — migrate or suspend them first",
  entityCommunity: "community",
  entityPickup: "pickup point",

  colPickupNo: "Pickup no.",
  colName: "Name",
  colType: "Type",
  colCarrier: "Host merchant",
  colTimes: "Arrival / opening hours",
  colFeeRate: "Service fee rate",
  zeroFee: "Unpaid",
  ariaFeeRate: "Rate (basis points)",
  save: "Save",
  cancel: "Cancel",
  colStatus: "Status",
  btnSuspend: "Suspend",
  btnActivate: "Activate",
  btnFinishMigrate: "Finish migration",

  col30dAccept: "Handled in 30 days",
  suspectPro: "Likely commercial",
  colFiledAt: "Registered at",
  btnPauseIntake: "Pause intake",

  readOnlyCommunityWhat: "community launch & geofence settings",
  readOnlyCommunityNote: "cannot launch, change the radius or archive",
  readOnlyPickupWhat: "pickup point registration & activation",
  readOnlyPickupNote: "cannot activate, change the fee rate or archive",

  neighborNotice:
    "Neighbour pickup points that handled {n} or more parcels in the last 30 days (suggested threshold, ADR-005 §F6). That frequency is no longer “helping a neighbour out once” — it is running an unlicensed pickup point, and needs a human review to either convert it to a fixed point or suspend it.",
  searchGrid: "Search community no. / name / grid",
  searchPickup: "Search pickup no. / name / address",
  filterOpened: "Filter by launch status",
  filterOpenedAll: "Any launch status",
  filterType: "Filter by type",
  filterTypeAll: "All types",
  filterStatus: "Filter by status",
  filterStatusAll: "All statuses",

  emptyGrid: "No communities match these filters. Clear the filters, or create a community under a city grid first.",
  emptyPickup: "No pickup points match these filters. A pickup point is where fulfillment lands — without one, goods arrive and nobody can redeem them.",
  emptyNeighbor: "No temporary pickup point is over the threshold ({n}+ parcels in 30 days). That is good news, not missing data.",
};

export const COMMUNITIES_COPY: PageCopy<typeof zh> = { zh, en };
