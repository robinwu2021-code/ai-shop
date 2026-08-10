// 覆盖范围：认证登录 + 工作台（P-16.1）。端点前缀 /ops/**（C 端是 /mp/**）。
import { client } from "../http-client";
import type { Role } from "@/lib/auth";

/** 后端 `/ops/auth/login` 的原样响应。**与 ops-web 的 LoginResp 不同形** */
interface BackendLogin {
  token: string;
  staff?: {
    staffNo: string;
    username: string;
    realName: string;
    roles?: string[];
    perms?: string[];
    status?: string;
  };
}

/**
 * 后端角色码 → ops-web 角色码。
 *
 * 后端目前只有四个（Perms.ROLE_PERMS），ops-web 的联合类型有十一个 ——
 * 多出来的七个后端还没有，登录时不可能出现。认不出来的一律落到最小权限，
 * **不是落到 SUPER_ADMIN**：认不出角色时给全权，是这类映射最坏的失败方式。
 */
const BACKEND_ROLE: Record<string, Role> = {
  SUPER_ADMIN: "SUPER_ADMIN",
  BD: "MERCHANT_BD",
  GOODS_OPS: "PRODUCT_OPS",
  SUPPORT: "CS",
};

function toOpsRole(backendRole?: string): Role {
  // 认不出来就当分析员（只读），而不是超管
  return (backendRole && BACKEND_ROLE[backendRole]) || "ANALYST";
}
import type { DashboardApi } from "../contracts/dashboard";

export const dashboardHttp: DashboardApi = {
  /*
   * 后端只认凭据，角色由它自己从 STAFF 账号上取 —— 前端传角色等于自己给自己授权。
   *
   * **这里必须做一次映射**：后端返回的是 `{token, staff:{username, roles:[…], perms:[…]}}`，
   * 而 ops-web 用的是扁平的 `{username, role, token}`。直接当 LoginResp 用的话
   * `role` 恒为 undefined —— 而权限判定读的是 `ROLE_PERMS[role]`，
   * 于是**连真后端登录后运营端所有按钮都失效**。mock 下 role 正常，所以开发期看不出来。
   *
   * 角色码两端也不同名（后端 BD / GOODS_OPS / SUPPORT），在这里翻译一次，
   * 不把两套词都放进 Role 联合类型 —— 那只会让「这个角色到底叫什么」有两个答案。
   */
  login: async (username, password) => {
    const raw = await client.post<BackendLogin>("/ops/auth/login", { username, password });
    const staff = raw?.staff;
    return {
      username: staff?.username ?? username,
      role: toOpsRole(staff?.roles?.[0]),
      token: raw?.token ?? "",
    };
  },
  getDashboardKpi: () => client.get("/ops/dashboard/kpi"),
  getDashboardTrend: () => client.get("/ops/dashboard/trend"),
  getAcquisitionFunnel: () => client.get("/ops/dashboard/funnel"),
};
