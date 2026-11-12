"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PayServiceOnboardingChannel, PayServiceOnboardingRow } from "@/lib/api/contracts/merchant";
import { useAuth, scopeOf } from "@/lib/auth";
import { useCan } from "@/lib/use-can";
import { fmtTime } from "@/lib/utils";
import { usePaging } from "@/lib/use-paging";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Pagination } from "@/components/ui/misc";
import { Toolbar } from "@/components/ui/toolbar";
import { HelpNote } from "@/components/ui/help-note";
import { Button } from "@/components/ui/button";
import type { MerchantsCopy } from "./copy";

function statusLabel(value: string | null, labels: Record<string, string>) {
  return value == null ? "—" : labels[value] ?? value;
}

function sourceLabels(c: MerchantsCopy) {
  return { NONE: c.obStateNone, PENDING: c.obStatePending, SUBMITTED: c.obStatePending, NEED_MORE: c.obStateNeedMore,
    REJECTED: c.obStateRejected, APPROVED: c.obStateApproved };
}

function ChannelFacts({ row, c }: { row: PayServiceOnboardingChannel; c: MerchantsCopy }) {
  const channelLabels = { DRAFT: c.obStateDraft, SUBMITTED: c.obStateSubmitted,
    CHANNEL_REVIEW: c.obStateChannelReview, NEED_SIGN: c.obStateNeedSign,
    APPROVED: c.obStateApproved, REJECTED: c.obStateRejected,
    CANCELLED: c.obStateCancelled, SUPERSEDED: c.obStateSuperseded };
  const handoffLabels = { PENDING: c.obStateHandoffPending, ACCEPTED: c.obStateAccepted,
    KEY_RECEIPT_MISSING: c.obStateKeyReceiptMissing };
  const bindingLabels = { NOT_REQUIRED: c.obStateNotRequired, PENDING: c.obStateBindingPending,
    BOUND: c.obStateBound, FAILED: c.obStateFailed };
  const facts = [
    [c.obOnboardingNo, row.onboardingNo], [c.obColChannel, row.payChannel],
    [c.obColStatus, statusLabel(row.status, channelLabels)], [c.obReviewStatus, statusLabel(row.reviewStatus, sourceLabels(c))],
    [c.obSubmitOutcome, statusLabel(row.submitOutcome, { ACCEPTED: c.obStateAccepted })], [c.obResultCode, row.resultCode],
    [c.obHandoffStatus, statusLabel(row.handoffStatus, handoffLabels)], [c.obBindingStatus, statusLabel(row.ezpayBindingStatus, bindingLabels)],
    [c.obColBlocker, row.reason], [c.obUpdatedAt, row.updatedAt == null ? null : fmtTime(row.updatedAt)],
  ];
  return <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 border-b py-2 last:border-0">
    {facts.map(([label, value]) => <div key={label} className="contents">
      <dt className="txt-caption text-muted-foreground">{label}</dt>
      <dd className="break-words">{value ?? "—"}</dd>
    </div>)}
  </dl>;
}

/** 仅展示 NearPay 已存事实；所有提交、通道回查和复核操作留在 NearPay。 */
export function OnboardingTab({ c }: { c: MerchantsCopy }) {
  const allow = useCan();
  const canRead = allow("merchant:admission:read");
  const auth = useAuth();
  const [keyword, setKeyword] = useState("");
  const { page, setPage, size, setSize } = usePaging(20);
  const q = { keyword, page, size };
  const list = useQuery({
    queryKey: ["near-pay-onboarding", auth.username, auth.token, scopeOf(auth), q],
    queryFn: () => api.nearPayOnboardingBoard(q),
    enabled: canRead,
  });
  const columns: Column<PayServiceOnboardingRow>[] = [
    { header: c.obColMerchant, cell: (r) => <div><div>{r.entityName}</div><div className="txt-caption tabular-nums text-muted-foreground">{r.entityNo}</div></div> },
    { header: c.obSourceStatus, cell: (r) => <dl className="space-y-1">
      {[[c.obSourceStatus, statusLabel(r.status, sourceLabels(c))], [c.obMerchantRef, r.merchantRef], [c.obLinkStatus, statusLabel(r.linkStatus, { VERIFIED: c.obStateVerified, PENDING: c.obStateVerifyPending, VERIFY_PENDING: c.obStateVerifyPending, MISMATCH: c.obStateMismatch })], [c.obProfileStatus, statusLabel(r.profileStatus, sourceLabels(c))], [c.obProfileReason, r.reviewReason]].map(([label, value]) => <div key={label}><dt className="txt-caption text-muted-foreground">{label}</dt><dd>{value ?? "—"}</dd></div>)}
    </dl> },
    { header: c.obChannelFacts, cell: (r) => r.onboardings.length ? <div>{r.onboardings.map((row) => <ChannelFacts key={row.onboardingNo} row={row} c={c} />)}</div> : <span className="text-muted-foreground">{c.obNoApplication}</span> },
    { header: c.obColActions, cell: (r) => r.onboardingOpsUrl ? <a className="text-primary-ink underline focus-ring" href={r.onboardingOpsUrl} target="_blank" rel="noopener noreferrer">{c.obOpenNearPay}</a> : null },
  ];
  if (!canRead) return null;
  return <>
    <HelpNote className="mb-3">{c.obNotice}</HelpNote>
    <Toolbar search={keyword} onSearch={(value) => { setKeyword(value); setPage(1); }} searchPlaceholder={c.obSearchPh}>
      <Button variant="outline" disabled={list.isFetching} onClick={() => list.refetch()}>{c.obReload}</Button>
    </Toolbar>
    <DataTable columns={columns} rows={list.data?.records} loading={list.isPending} rowKey={(r) => r.entityNo} empty={c.obEmpty} error={list.error} onRetry={() => list.refetch()} />
    <Pagination page={page} size={size} total={list.data?.total ?? 0} onPage={setPage} onSize={setSize} sizes={[10, 20]} />
  </>;
}
