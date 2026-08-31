// 看板域（矩阵 P-16.1 数据看板）。
import type { Role } from "../auth";

/** 增长漏斗的四段。扫码 → 进店 → 注册 → 首单 */
export type FunnelStep = "SCAN" | "ENTER_STORE" | "REGISTER" | "FIRST_ORDER";

export interface LoginResp {
  /** 登录名 */
  username: string;
  /** 角色。**权限判定以后端为准**，前端只做菜单裁剪 */
  role: Role;
  /** 访问令牌。STAFF 池，与 C 端、B 端账号不通用 */
  token: string;
  /**
   * **后端下发的权限码**（`staff.perms`）。判权以它为准。
   *
   * `["*"]` = 超管通配。前端的 UI 码要先经 `UI_PERM_MAP` 翻译成后端码
   * 再来这里查 —— 两边的粒度不同（前端 45 个、后端 14 个），
   * 直接比会全判 false。
   */
  perms: string[];
  /** 商家运营（BD）等受限角色的数据域；平台全量角色为空 */
  merchantNo?: string;
  /** 受限角色的社区数据域 */
  communityNo?: string;
}

/** KPI 卡（金额为最小货币单位整数）。 */
export interface DashboardKpi {
  /** 成交额（最小货币单位整数） */
  gmv: number;
  /** 订单数 */
  orderCount: number;
  /** 客单价 */
  avgOrderValue: number;
  /** 待审商家数（P-11.1.1 提审队列） */
  pendingMerchantAudit: number;
  /** 待处理售后（P-6.1.1 工单池） */
  pendingAfterSale: number;
  /** 今日核销率（P-5.1.3 核销监控），0–1 */
  redeemRate: number;
}

export interface TrendPoint {
  /** 日期 YYYY-MM-DD */
  date: string;
  /** 当日成交额（最小货币单位整数） */
  gmv: number;
  /** 当日订单数 */
  orderCount: number;
}

/** 获客漏斗的一行（P-16.1.4 扫码→进店→注册→首单）。
 *  ⚠️ 此前 interface 与环节枚举撞名叫 FunnelStep，字段写成 `step: FunnelStep` —— 自我引用 */
export interface FunnelRow {
  /** 漏斗环节：扫码 → 进店 → 注册 → 首单 */
  step: FunnelStep;
  /** 该环节人数 */
  count: number;
}


/**
 * 商家经营排行的一行（P-16.1.2 / P-16.1.3）—— 大盘之下的第一层下钻。
 *
 * 大盘回答「平台整体怎么样」，运营下一句必然是「哪几家在拉高、哪几家在拖后腿」。
 */
export interface MerchantRankRow {
  /** 商家主体号 */
  merchantNo: string;
  /** 商家名。**必须有** —— 只给编号的话运营还要再查一次「这家是谁」 */
  merchantName: string;
  /** 成交额（最小货币单位整数） */
  gmv: number;
  /** 订单数 */
  orderCount: number;
  /** 客单价（最小货币单位整数） */
  avgOrderValue: number;
  /** 售后单数 */
  afterSaleCount: number;
  /** 售后率 0–1。与 GMV 并列才看得出「卖得多」是不是「赔得也多」 */
  afterSaleRate: number;
}

/** 服务端下发的菜单分区（`GET /ops/menu`）。 */
export interface MenuFunction {
  /** 功能点编码，菜单树的一级节点 */
  functionCode: string;
  /** 菜单显示名 */
  name: string;
  /** 图标名；为空由前端按 functionCode 兜底 */
  icon?: string | null;
  /** 一级节点自身的落地路径；为空表示它只是个分组 */
  href?: string | null;
  /** 同级排序，小的在前 */
  sort: number;
  /** 这个功能点下的二级菜单/按钮 */
  points: MenuPoint[];
}

export interface MenuPoint {
  /** 功能点下的点编码，菜单树的二级节点 */
  pointCode: string;
  /** 菜单显示名 */
  name: string;
  /** 侧栏里的分组标题；为空表示不分组 */
  groupName?: string | null;
  /** 落地路径；为空表示还没有页面 */
  href?: string | null;
  /** 前端页面自己判的码。与 permCode 不同：**它管的是能不能看见按钮，不是能不能调接口** */
  uiPermCode?: string | null;
  /** 后端权限码。**null = 不受权限约束** —— 与 NOT_IMPLEMENTED 是两回事 */
  permCode?: string | null;
  /** IMPLEMENTED / NOT_IMPLEMENTED / UNMAPPED */
  backendStatus: string;
  /** 后端通了但前端页面还没做完 */
  uiReady: boolean;
  /** 对应需求矩阵里的编号（如 `P-11.1`），用于回溯这个菜单项是哪条需求 */
  matrixCode?: string | null;
  /** MENU 菜单项 / ACTION 页面内按钮级授权（菜单不渲染 ACTION） */
  pointType: string;
  /** 同级排序，小的在前 */
  sort: number;
}
