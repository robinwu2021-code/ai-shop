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
