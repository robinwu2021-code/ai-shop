// 角色页「支付中心数据范围」只读栏的文案（TDD-支付运营端经网关转发与权限下沉 §10 B2）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {
  payScopeTitle: "支付中心数据范围",
  payScopeHint: "该角色在支付中心能看哪些商户（near-pay 数据范围，按角色授予，两人审批）。这里只读，增改在支付中心「数据范围」页签。",
  payScopeEmpty: "该角色没有生效的支付数据范围：持有支付权限码的成员在支付中心看不到任何商户（超级管理员除外）。",
  payScopeError: "支付数据范围读取失败。",
  payScopeAllMerchants: "全部商户",
  /** `{n}` 是商户数 */
  payScopeMerchants: "{n} 家商户",
  payScopeFundsNone: "资金不可见",
  payScopeFundsMasked: "资金脱敏只读",
  /** `{date}` 是到期日 */
  payScopeExpires: "到期 {date}",
  payScopeOpen: "去数据范围页签",
};

const en: typeof zh = {
  payScopeTitle: "Payment center data scope",
  payScopeHint: "Which merchants this role can see in the payment center (near-pay data scope, granted per role with two-person approval). Read-only here; manage it in the payment center Data scope tab.",
  payScopeEmpty: "This role has no active payment data scope: members holding payment permission codes see no merchants in the payment center (super admins excepted).",
  payScopeError: "Payment data scope could not be loaded.",
  payScopeAllMerchants: "All merchants",
  payScopeMerchants: "{n} merchants",
  payScopeFundsNone: "Funds hidden",
  payScopeFundsMasked: "Funds masked, read-only",
  payScopeExpires: "Expires {date}",
  payScopeOpen: "Open Data scope tab",
};

export const ROLE_PAY_SCOPE_COPY: PageCopy<typeof zh> = { zh, en };
