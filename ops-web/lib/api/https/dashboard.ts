// 覆盖范围：认证登录 + 工作台（P-16.1）。端点前缀 /ops/**（C 端是 /mp/**）。
import { client } from "../http-client";
import type { DashboardApi } from "../contracts/dashboard";

export const dashboardHttp: DashboardApi = {
  login: (username, role, scope) => client.post("/ops/auth/login", { username, role, ...scope }),
  getDashboardKpi: () => client.get("/ops/dashboard/kpi"),
  getDashboardTrend: () => client.get("/ops/dashboard/trend"),
  getAcquisitionFunnel: () => client.get("/ops/dashboard/funnel"),
};
