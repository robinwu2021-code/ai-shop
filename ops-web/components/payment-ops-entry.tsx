"use client";

import { Button } from "@/components/ui/button";
import { HelpNote } from "@/components/ui/help-note";
import { useAuth } from "@/lib/auth";
import { PAYMENT_WORKSPACES, paymentWorkspaceHref, shareOpsToken, type PaymentWorkspace } from "@/lib/auth/ops-bridge";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";

const COPY = {
  zh: {
    channels: ["支付渠道", "维护支付端渠道目录、启停与可用产品。", "打开支付渠道"],
    merchants: ["支付商户", "查询支付商户档案，按授权新建商户、代填资料、保存草稿并提交审核。", "打开支付商户工作区"],
    onboarding: ["渠道进件", "为已建支付商户创建渠道申请、代填资料、保存草稿、提交平台审核并跟踪渠道送件。", "打开渠道进件工作区"],
    payments: ["支付记录", "查询支付主单、支付尝试、退款及渠道结果。业务订单的履约与售后在业务订单中处理。", "打开支付记录"],
    "payment-exceptions": ["支付异常", "处理支付补查、转人工与异常交易；按支付端原有权限执行。", "打开支付异常"],
    noAccess: "当前账号没有此支付工作区的查看权限。",
  },
  "zh-TW": {
    channels: ["支付通路", "維護支付端通路目錄、啟停與可用產品。", "開啟支付通路"],
    merchants: ["支付商戶", "查詢支付商戶檔案，依授權建立商戶、代填資料、儲存草稿並提交審核。", "開啟支付商戶工作區"],
    onboarding: ["通路進件", "為已建支付商戶建立通路申請、代填資料、儲存草稿、提交平台審核並追蹤通路送件。", "開啟通路進件工作區"],
    payments: ["支付紀錄", "查詢支付主單、支付嘗試、退款及通路結果。業務訂單的履約與售後在業務訂單中處理。", "開啟支付紀錄"],
    "payment-exceptions": ["支付異常", "處理支付補查、轉人工與異常交易；依支付端原有權限執行。", "開啟支付異常"],
    noAccess: "目前帳號沒有此支付工作區的檢視權限。",
  },
  en: {
    channels: ["Payment channels", "Manage the payment service channel catalog, availability and products.", "Open payment channels"],
    merchants: ["Payment merchants", "View merchant profiles, create authorized merchants, fill and save profile drafts, and submit them for review.", "Open merchant workspace"],
    onboarding: ["Channel onboarding", "Create channel applications for existing payment merchants, fill materials, save drafts, submit for platform review and track channel submission.", "Open onboarding workspace"],
    payments: ["Payment records", "View payment orders, attempts, refunds and channel results. Fulfilment and after-sales remain with business orders.", "Open payment records"],
    "payment-exceptions": ["Payment exceptions", "Handle payment recovery, escalations and exceptions under the payment service's existing permissions.", "Open payment exceptions"],
    noAccess: "Your account does not have permission to view this payment workspace.",
  },
} as const;

/** Reuses the live payment workspace; the destination revalidates session and data scope. */
export function PaymentOpsEntry({ workspace }: { workspace: PaymentWorkspace }) {
  const { locale } = useI18n();
  const c = COPY[locale];
  const allow = useCan();
  const token = useAuth((s) => s.token);
  const [title, description, action] = c[workspace];
  const permitted = allow(PAYMENT_WORKSPACES[workspace].permission);
  return <section className="mb-4 space-y-3" aria-label={title}>
    <HelpNote>{description}</HelpNote>
    {permitted ? <Button onClick={() => {
      if (token) shareOpsToken(token);
      window.location.assign(paymentWorkspaceHref(window.location.origin, locale, workspace));
    }}>{action}</Button> : <p className="txt-caption text-muted-foreground">{c.noAccess}</p>}
  </section>;
}
