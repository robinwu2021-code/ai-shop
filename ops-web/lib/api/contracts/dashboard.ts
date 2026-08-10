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
  getDashboardKpi(): Promise<DashboardKpi>;
  getDashboardTrend(): Promise<TrendPoint[]>;
  getAcquisitionFunnel(): Promise<FunnelRow[]>;
}
