"use client";

// 平台端登录态。角色取自 docs/requirements/需求矩阵-三端.md §2.3（11 类平台岗位）。
// ⚠️ 平台端与 B 端商家账号是两个池子：本处是 STAFF 池（Bearer realm=STAFF + RBAC + 数据域），
//    商家账号走 B 端；不要在这里加商家角色。
import { create } from "zustand";
import { persist } from "zustand/middleware";
import { MAIN_TENANT, type DataScope } from "./types/common";

export type Role =
  | "SUPER_ADMIN" // 超级管理员
  | "PRODUCT_OPS" // 商品/类目运营
  | "CAMPAIGN_OPS" // 活动运营
  | "COMMUNITY_OPS" // 社区运营
  | "MERCHANT_BD" // 商家运营（BD）
  | "AUDITOR" // 审核员
  | "CS" // 客服
  | "FINANCE" // 财务/结算
  | "RISK" // 风控
  | "ANALYST" // 数据分析
  | "TECH_OPS"; // 技术运维

export const AUTH_STORAGE_KEY = "shop-ops-auth";

export interface AuthState {
  username: string;
  role: Role;
  /** 后端兼容：随请求头透传，MVP 恒为 MAIN（矩阵 P-17.1.6 预留） */
  tenantNo: string;
  /** 数据域：BD 可被限定到某商家、社区运营可被限定到某社区（矩阵 §2.3 权限模型） */
  merchantNo: string;
  communityNo: string;
  pickupNo: string;
  token: string;
  /**
   * **后端下发的权限码**（`staff.perms`）。判权以它为准，不是本地的 ROLE_PERMS。
   *
   * 后端一直在下发，而前端此前一个字节都没读过 —— `can()` 查的是前端自己
   * 写死的角色表。两套各自演化的结果是：前端放行、后端 403（点了报「没有操作权限」），
   * 或者前端拦住、后端本来允许（功能存在而没人找得到入口）。
   *
   * `["*"]` = 超管通配。空数组 = 零权限（**不是「还没加载」**：
   * 未登录时本来就该什么都看不见）。
   */
  perms: string[];
  login: (v: {
    username: string;
    role: Role;
    token: string;
    perms?: string[];
    merchantNo?: string;
    communityNo?: string;
    pickupNo?: string;
  }) => void;
  logout: () => void;
  loggedIn: () => boolean;
}

const EMPTY_SCOPE = { merchantNo: "", communityNo: "", pickupNo: "" };

export const useAuth = create<AuthState>()(
  persist(
    (set, get) => ({
      username: "",
      role: "SUPER_ADMIN",
      tenantNo: MAIN_TENANT,
      ...EMPTY_SCOPE,
      token: "",
      perms: [],
      login: (v) => set({ ...EMPTY_SCOPE, perms: [], ...v, tenantNo: MAIN_TENANT }),
      // 退出要清 perms：留着的话，下一个人在同一台机器上登录、
      // 在 perms 拉回来之前的那一瞬看到的是上一个人的入口
      logout: () => set({ username: "", token: "", perms: [], ...EMPTY_SCOPE }),
      loggedIn: () => !!get().token,
    }),
    { name: AUTH_STORAGE_KEY },
  ),
);

/** 供非 React 层（lib/api）读取当前身份 → 拼请求头。 */
export function currentAuth(): AuthState | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY);
    if (!raw) return null;
    return JSON.parse(raw).state as AuthState;
  } catch {
    return null;
  }
}

/**
 * 当前身份的数据域。空字段 = 不限定（全量）。
 * ⚠️ 前端带 scope 只是为了让 mock 与真实后端行为一致、少一次全量拉取；
 *    **越权拦截以后端为准**（矩阵 §2.3：不靠前端隐藏）。
 */
export function scopeOf(a: AuthState | null): DataScope {
  return {
    tenantNo: a?.tenantNo || MAIN_TENANT,
    merchantNo: a?.merchantNo || undefined,
    communityNo: a?.communityNo || undefined,
    pickupNo: a?.pickupNo || undefined,
  };
}
