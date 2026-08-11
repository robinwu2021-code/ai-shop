// 覆盖范围：认证登录 + 工作台（P-16.1）。
import type { DashboardKpi, FunnelRow, LoginResp, TrendPoint } from "@/lib/types";
import type { Role } from "@/lib/auth";

export type { LoginResp };

export interface DashboardApi {
  /** 登录换后端 token。**后端据 token 里的角色鉴权，不认客户端传的角色头。** */
  /**
   * 登录。**角色在返回值里，不在入参里** —— 让调用方指定自己的角色，
   * 等于把权限交给被鉴权的一方。
   */
  login(username: string, password: string): Promise<LoginResp>;
  /**
   * 拿当前登录人的最新身份（`GET /ops/auth/me`）。
   *
   * **为什么不能只在登录时拿一次**：perms 存在 localStorage 里，
   * 管理员改了某人的角色，那个人要重新登录才生效 —— 而他不会知道要重新登录，
   * 他看到的是「我明明有这个权限，按钮却不见了」（或者反过来，点了报 403）。
   * 更硬的一种：换了版本后本地存的是旧结构（没有 perms 字段），
   * 于是导航整个空掉，而 token 还是有效的 —— 用户卡在一个看不出原因的空壳里。
   */
  me(): Promise<LoginResp>;
  getDashboardKpi(): Promise<DashboardKpi>;
  getDashboardTrend(): Promise<TrendPoint[]>;
  getAcquisitionFunnel(): Promise<FunnelRow[]>;
}
