// 覆盖范围：认证登录 + 工作台（P-16.1）。端点前缀 /ops/**（C 端是 /mp/**）。
import { client } from "../http-client";
import type { DashboardApi } from "../contracts/dashboard";

export const dashboardHttp: DashboardApi = {
  // 后端只认凭据，角色由它自己从 STAFF 账号上取 —— 前端传角色等于自己给自己授权
  login: (username, password) => client.post("/ops/auth/login", { username, password }),
  getDashboardKpi: () => client.get("/ops/dashboard/kpi"),
  getDashboardTrend: () => client.get("/ops/dashboard/trend"),
  getAcquisitionFunnel: () => client.get("/ops/dashboard/funnel"),
};
