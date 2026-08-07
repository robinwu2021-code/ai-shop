// 覆盖范围：认证登录 + 工作台（P-16.1）。
import * as db from "@/lib/mock/db";
import type { DashboardApi } from "../contracts/dashboard";
import { wait } from "./_wait";

export const dashboardMock: DashboardApi = {
  login: (username, role, scope) =>
    wait({ username, role, token: `mock-${role}`, ...scope }, 350),
  getDashboardKpi: () => wait(db.kpi),
  getDashboardTrend: () => wait(db.trend),
  getAcquisitionFunnel: () => wait(db.funnel),
};
