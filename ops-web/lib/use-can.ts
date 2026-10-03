"use client";

import { useAuth } from "./auth";
import { can } from "./permissions";

/** 页面内按钮级鉴权：const allow = useCan(); allow('order:refund:audit')。 */
export function useCan() {
  // **读 perms 不是 role** —— 后端下发的才是判权依据；
  // role 只用于展示（「你是商家运营」）与菜单分组
  const perms = useAuth((s) => s.perms);
  return (code: string) => can(perms, code);
}
