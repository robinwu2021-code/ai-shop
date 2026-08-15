import { backendPermsOf } from "@/lib/permissions";
// 覆盖范围：认证登录 + 工作台（P-16.1）。
import * as db from "@/lib/mock/db";
import type { DashboardApi } from "../contracts/dashboard";
import { fail } from "@/lib/biz-error";
import { wait } from "./_wait";
import { currentAuth, type Role } from "@/lib/auth";
import { NAV } from "@/lib/nav";
import { can } from "@/lib/permissions";

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
    return wait({ username, role, token: `mock-${role}`, perms: backendPermsOf(role) }, 350);
  },
  /*
   * mock 的 me 从当前登录态回读角色 —— 不能凭空返回超管，
   * 那会让「刷新后权限被放大」在 mock 下永远看不出来。
   */
  me: () => {
    const a = currentAuth();
    if (!a?.token) fail("未登录", "Not signed in");
    return wait({ username: a.username, role: a.role, token: "", perms: backendPermsOf(a.role) }, 120);
  },
  /*
   * mock 的菜单从静态 NAV 造 —— 与真后端返回同形状。
   * 不造的话 mock 模式下菜单会整个空掉，而那与「权限配错」长得一样。
   */
  // mock 下永远「成功」——与真实现同口径：不泄露账号是否存在
  forgotPassword: () => wait(undefined as unknown as void, 400),
  resetPassword: (token) => {
    if (token !== "mock-token") fail("重置码无效或已过期，请重新申请",
      "Reset code is invalid or expired. Please request a new one");
    return wait(undefined as unknown as void, 400);
  },
  menu: () => {
    const a = currentAuth();
    const fns = NAV.map((s, i) => ({
      functionCode: `OPS_${s.key.toUpperCase()}`,
      name: s.label, icon: s.icon, href: s.href, sort: (i + 1) * 10,
      points: (s.children ?? [])
        .filter((l) => !l.perm || can(a?.perms, l.perm))
        .map((l, j) => ({
          pointCode: `OPS_${s.key.toUpperCase()}_${j}`, name: l.label,
          groupName: l.group ?? null, href: l.href, uiPermCode: l.perm ?? null,
          permCode: null, backendStatus: "IMPLEMENTED", uiReady: l.ready !== false,
          matrixCode: l.matrix ?? null, pointType: "MENU", sort: (j + 1) * 10,
        })),
    })).filter((f) => f.points.length > 0 || f.functionCode === "OPS_DASHBOARD");
    return wait(fns, 120);
  },
  getDashboardKpi: () => wait(db.kpi),
  getDashboardTrend: () => wait(db.trend),
  getAcquisitionFunnel: () => wait(db.funnel),
  getMerchantRanking: () => wait(db.merchantRanking),
};
