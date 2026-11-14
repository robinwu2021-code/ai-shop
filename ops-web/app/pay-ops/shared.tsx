"use client";
// 支付运营各页共用的小件：双语短句、状态徽章。页面正文按运营端惯例中英两份（usePointsText 同一种写法）。
import { useI18n } from "@/lib/i18n";
import { Badge } from "@/components/ui/badge";

export function useTT() {
  const { locale } = useI18n();
  return (zh: string, en: string) => (locale === "en" ? en : zh);
}

type Tone = "success" | "warning" | "danger" | "info" | "muted" | "default";

const TONES: Record<string, Tone> = {
  SUCCEEDED: "success", SUCCESS: "success", DELIVERED: "success", RESOLVED: "success", APPROVED: "success",
  PARTIALLY_REFUNDED: "info", REFUNDED: "info", DISPATCHED: "info", AUTO_REFUNDING: "info",
  PENDING_REVIEW: "warning", PENDING_SECOND_REVIEW: "warning", OPEN: "warning", FAILED: "danger", GIVEN_UP: "danger",
  REJECTED: "danger", CLOSED: "muted", IGNORED: "muted", CREATED: "default", AWAITING_PAYMENT: "default", PENDING: "default",
  // 接入与密钥（M5b）
  ACTIVE: "success", ACCEPTED: "success", ROTATING: "info", VERIFIED: "info", DRAFT: "default", DUPLICATE: "muted",
  RETIRED: "muted", REVOKED: "danger", DISABLED: "muted", SUSPENDED: "warning", EXPIRED: "muted",
  REJECTED_AUTH: "danger", REJECTED_PARAM: "warning", REJECTED_FORBIDDEN: "danger", ERROR: "danger",
};

const NAMES: Record<string, [string, string]> = {
  CREATED: ["已建单", "Created"], AWAITING_PAYMENT: ["待支付", "Awaiting payment"], SUCCEEDED: ["已支付", "Paid"],
  PARTIALLY_REFUNDED: ["部分退款", "Partly refunded"], REFUNDED: ["已全退", "Refunded"], CLOSED: ["已关闭", "Closed"],
  PENDING_REVIEW: ["待复核", "Awaiting review"], PENDING_SECOND_REVIEW: ["待第二人复核", "Awaiting 2nd review"],
  APPROVED: ["复核通过", "Approved"], DISPATCHED: ["退款中", "Refunding"], FAILED: ["失败", "Failed"],
  REJECTED: ["已驳回", "Rejected"], REQUESTED: ["已提交", "Requested"], PENDING: ["待投递", "Pending"],
  DELIVERED: ["已送达", "Delivered"], GIVEN_UP: ["已放弃", "Given up"], OPEN: ["待处置", "Open"],
  AUTO_REFUNDING: ["原路退回中", "Refunding"], RESOLVED: ["已处置", "Resolved"], IGNORED: ["无需处置", "Ignored"],
  SUCCESS: ["成功", "Success"], INIT: ["待发起", "Init"],
  ACTIVE: ["生效中", "Active"], ROTATING: ["轮换重叠期", "Rotating"], VERIFIED: ["已验证待启用", "Verified"],
  DRAFT: ["已录入", "Draft"], RETIRED: ["已退役", "Retired"], REVOKED: ["已吊销", "Revoked"], DISABLED: ["已停用", "Disabled"],
  SUSPENDED: ["已暂停", "Suspended"], EXPIRED: ["已过期", "Expired"], ACCEPTED: ["通过", "Accepted"],
  REJECTED_AUTH: ["鉴权失败", "Auth rejected"], REJECTED_PARAM: ["参数不对", "Bad request"],
  REJECTED_FORBIDDEN: ["无权", "Forbidden"], DUPLICATE: ["重复", "Duplicate"], ERROR: ["出错", "Error"],
};

export function StatusBadge({ status }: { status: string }) {
  const tt = useTT();
  const n = NAMES[status];
  return <Badge tone={TONES[status] ?? "muted"}>{n ? tt(n[0], n[1]) : status}</Badge>;
}
