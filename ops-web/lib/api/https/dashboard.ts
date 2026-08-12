// 覆盖范围：认证登录 + 工作台（P-16.1）。端点前缀 /ops/**（C 端是 /mp/**）。
import { client } from "../http-client";
import type { Role } from "@/lib/auth";

/** 后端 `/ops/auth/login` 的原样响应。**与 ops-web 的 LoginResp 不同形** */
interface BackendStaff {
  staffNo: string;
  username: string;
  realName: string;
  roles?: string[];
  perms?: string[];
  status?: string;
  /**
   * 数据域（2026-08-11 后端补上）。空 = 不限定。
   *
   * ⚠️ **裁剪尚未生效** —— 后端各域查询还没按它过滤。
   * 前端照常带着它走（`scopeOf()` 一直在做），但别把它当成一道保护。
   */
  merchantNo?: string;
  communityNo?: string;
  pickupNo?: string;
}

interface BackendLogin {
  token: string;
  staff?: BackendStaff;
}

/**
 * 后端角色码 → ops-web 角色码。
 *
 * 后端 Perms.ROLE_PERMS 与 ops-web 的联合类型都是十一个岗位（矩阵 §2.3）——
 * 十一个都配齐了（2026-08-11）。认不出来的一律落到最小权限，
 * **不是落到 SUPER_ADMIN**：认不出角色时给全权，是这类映射最坏的失败方式。
 */
const BACKEND_ROLE: Record<string, Role> = {
  SUPER_ADMIN: "SUPER_ADMIN",
  // 这三个是异名同义的历史遗留
  BD: "MERCHANT_BD",
  GOODS_OPS: "PRODUCT_OPS",
  SUPPORT: "CS",
  // 后端 2026-08-11 补的七个，两边同名 —— 列出来而不是靠「认不出就原样返回」，
  // 因为那样一来拼错的角色码也会被当成合法角色放进联合类型
  CAMPAIGN_OPS: "CAMPAIGN_OPS",
  COMMUNITY_OPS: "COMMUNITY_OPS",
  AUDITOR: "AUDITOR",
  FINANCE: "FINANCE",
  RISK: "RISK",
  ANALYST: "ANALYST",
  TECH_OPS: "TECH_OPS",
};

/**
 * 导出给 `https/iam.ts` 用：员工列表也要把后端角色码翻成 ops-web 的。
 * **只能有一份** —— 两处各写一份翻译表，改一个角色名就会漏掉另一处，
 * 而漏掉的表现是那一列显示 `role.undefined`（实测过）。
 */
export function toOpsRole(backendRole?: string): Role {
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
      /*
       * perms 一直在响应里，而此前**这一行不存在** —— 拿到手就丢了，
       * 判权全靠前端本地的 ROLE_PERMS。两套各自演化的结果是
       * 「前端显示的权限与后端实际允许的没有任何关系」。
       */
      perms: staff?.perms ?? [],
      merchantNo: staff?.merchantNo,
      communityNo: staff?.communityNo,
      pickupNo: staff?.pickupNo,
    };
  },
  me: async () => {
    const staff = await client.get<BackendStaff>("/ops/auth/me");
    return {
      username: staff?.username ?? "",
      role: toOpsRole(staff?.roles?.[0]),
      // me 不换发 token —— 调用方保留手里那张
      token: "",
      perms: staff?.perms ?? [],
      merchantNo: staff?.merchantNo,
      communityNo: staff?.communityNo,
      pickupNo: staff?.pickupNo,
    };
  },
  menu: () => client.get("/ops/menu"),
  getDashboardKpi: () => client.get("/ops/dashboard/kpi"),
  getDashboardTrend: () => client.get("/ops/dashboard/trend"),
  getAcquisitionFunnel: () => client.get("/ops/dashboard/funnel"),
};
