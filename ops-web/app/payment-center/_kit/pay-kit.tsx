"use client";

// 支付中心的业务件（TDD-支付中心运营页面 §2.3）：认得支付域的词与权限来源，
// 底下全部复用 components/ui 的组合件。
import * as React from "react";
import Link from "next/link";
import { Copy } from "lucide-react";
import { ListPage, type ListPageProps } from "@/components/ui/list-page";
import { Notice } from "@/components/ui/notice";
import type { StatusMap } from "@/components/ui/status-badge";
import { useAuth } from "@/lib/auth";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { useLocaleStore } from "@/lib/stores/locale";
import { createPayOpsClient, type PayOpsClient } from "@/app/payment-center/_api/client";
import { createHttpPayCenterApi, isPayMock, type PayCenterApi } from "@/app/payment-center/_api/api";
import { createMockPayCenterApi } from "@/app/payment-center/_api/mock";
import type { DocumentType, RecognizeResult, RefundListRow } from "@/app/payment-center/_api/types";
import { api } from "@/lib/api";

/** 支付中心列表页：ListPage + 当前账号的判权 + 当前操作人的草稿。 */
export function PayListPage<T>(props: Omit<ListPageProps<T>, "allow" | "drafts"> & { draftPage?: string; resolve?: (id: string) => T | null | Promise<T | null> }) {
  const allow = useCan();
  const operator = useAuth((s) => s.username);
  const { draftPage, resolve, ...rest } = props;
  return (
    <ListPage<T>
      {...rest}
      allow={allow}
      syncUrl={rest.syncUrl ?? (resolve ? { resolve } : undefined)}
      drafts={draftPage && operator ? { operator, page: draftPage, resolve } : undefined}
    />
  );
}

/** 草稿定位用的操作人；未登录（或登录名为空）时返回 null，表单只做内存态。 */
export function useDraftOperator(): string | null {
  return useAuth((s) => s.username) || null;
}

/** 支付端客户端：带当前令牌与界面语言。 */
export function usePayOps(): PayOpsClient {
  return React.useMemo(() => createPayOpsClient({
    getToken: () => useAuth.getState().token,
    locale: () => useLocaleStore.getState().locale,
  }), []);
}

// mock 实例全页共用一份：切页签、关抽屉后数据还在，像真后端一样
let mockApi: PayCenterApi | null = null;

/** 支付中心接口：NEXT_PUBLIC_USE_MOCK≠0 走内存 mock，否则走 near-pay /pay-ops/v2。 */
export function usePayCenterApi(): PayCenterApi {
  const client = usePayOps();
  return React.useMemo(() => {
    if (isPayMock()) return (mockApi ??= createMockPayCenterApi());
    return createHttpPayCenterApi(client, {
      refunds: {
        list: (q) => api.payOpsRefunds(q) as Promise<RefundListRow[]>,
        review: (no, body) => api.payOpsReviewRefund(no, body) as Promise<RefundListRow>,
      },
      documents: { recognize: recognizeOnShop },
    });
  }, [client]);
}

/**
 * 证照识别走 shop 后端（`/ops/onboarding-documents/recognize`，multipart）。shop 的通用客户端不发 multipart，
 * 这里用同一个运营会话直接发；信封 {code,msg,data}。
 */
async function recognizeOnShop(file: File, documentType: DocumentType): Promise<RecognizeResult> {
  const form = new FormData();
  form.append("file", file, file.name);
  form.append("documentType", documentType);
  const token = useAuth.getState().token;
  const res = await fetch(`${process.env.NEXT_PUBLIC_API_BASE ?? ""}/ops/onboarding-documents/recognize`, {
    method: "POST", body: form, credentials: "same-origin",
    headers: { Accept: "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}), "Accept-Language": useLocaleStore.getState().locale },
  });
  const body = await res.json().catch(() => null) as { code?: number; msg?: string; data?: RecognizeResult } | null;
  if (!res.ok || !body || body.code !== 0 || !body.data) throw new Error(body?.msg || `证照识别失败（HTTP ${res.status}）`);
  return body.data;
}

/** 订单状态映射（喂 StatusBadge 与 FilterSelect）。 */
export function usePayOrderStatusMap(): StatusMap<"CREATED" | "PAYING" | "PAID" | "CLOSED" | "FAILED" | "REFUNDED"> {
  const { t } = useI18n();
  return React.useMemo(() => ({
    CREATED: { label: t("payKit.orderStatus.CREATED"), tone: "muted" },
    PAYING: { label: t("payKit.orderStatus.PAYING"), tone: "info" },
    PAID: { label: t("payKit.orderStatus.PAID"), tone: "success" },
    CLOSED: { label: t("payKit.orderStatus.CLOSED"), tone: "muted" },
    FAILED: { label: t("payKit.orderStatus.FAILED"), tone: "danger" },
    REFUNDED: { label: t("payKit.orderStatus.REFUNDED"), tone: "warning" },
  }), [t]);
}

/** 编号单元格：等宽显示 + 复制；给了 href 就可点去对应页（通常带 ?action=view&id=）。 */
export function PayRefCell({ value, href }: { value: string | null | undefined; href?: string }) {
  const { t } = useI18n();
  if (!value) return <span className="text-muted-foreground">—</span>;
  return (
    <span className="inline-flex items-center gap-1 font-mono txt-caption" onClick={(e) => e.stopPropagation()}>
      {href ? <Link href={href} className="text-primary-ink hover:underline focus-ring">{value}</Link> : value}
      <button
        type="button"
        aria-label={t("payKit.copy")}
        title={t("payKit.copy")}
        className="rounded-field p-0.5 text-muted-foreground hover:bg-accent focus-ring"
        onClick={() => { void navigator.clipboard?.writeText(value).then(() => notify.success(t("payKit.copied")), () => {}); }}
      >
        <Copy className="size-3" />
      </button>
    </span>
  );
}

/** 需要审批的修改：在编辑抽屉顶部说明，提交后引导去审核待办。 */
export function ApprovalSubmitNotice({ approvalsHref }: { approvalsHref?: string }) {
  const { t } = useI18n();
  return (
    <Notice tone="info" className="mb-4">
      <span>{t("payKit.approvalRequired")}</span>
      {approvalsHref && <Link href={approvalsHref} className="ms-2 text-primary-ink hover:underline focus-ring">{t("payKit.goApprovals")}</Link>}
    </Notice>
  );
}
