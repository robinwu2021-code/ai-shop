// 覆盖范围：认证登录 + 工作台（P-16.1）。
import type { DashboardKpi, FunnelStep, LoginResp, TrendPoint } from "@/lib/types";
import type { Role } from "@/lib/auth";

export type { LoginResp };

export interface DashboardApi {
  /** 登录换后端 token。**后端据 token 里的角色鉴权，不认客户端传的角色头。** */
  login(username: string, role: Role, scope?: { merchantNo?: string; communityNo?: string }): Promise<LoginResp>;
  getDashboardKpi(): Promise<DashboardKpi>;
  getDashboardTrend(): Promise<TrendPoint[]>;
  getAcquisitionFunnel(): Promise<FunnelStep[]>;
}
