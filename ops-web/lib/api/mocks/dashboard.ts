// 覆盖范围：认证登录 + 工作台（P-16.1）。
import * as db from "@/lib/mock/db";
import type { DashboardApi } from "../contracts/dashboard";
import { fail } from "@/lib/biz-error";
import { wait } from "./_wait";
import type { Role } from "@/lib/auth";

/** 与后端 DevSeeder 的四个运营账号一一对应 */
const MOCK_ROLE_OF: Record<string, Role> = {
  admin: "SUPER_ADMIN",
  bd: "MERCHANT_BD",
  goods: "PRODUCT_OPS",
  support: "CS",
};

export const dashboardMock: DashboardApi = {
  /*
   * mock 也按真实契约来：只收凭据，角色由「后端」给。
   * 按用户名映射角色 —— 与后端种子账号一致（admin/bd/goods/support），
   * 这样 mock 上点得通的路径，指向真后端也点得通。
   */
  login: (username, password) => {
    if (!password) fail("请输入密码", "Enter your password");
    const role = MOCK_ROLE_OF[username] ?? "SUPER_ADMIN";
    return wait({ username, role, token: `mock-${role}` }, 350);
  },
  getDashboardKpi: () => wait(db.kpi),
  getDashboardTrend: () => wait(db.trend),
  getAcquisitionFunnel: () => wait(db.funnel),
};
