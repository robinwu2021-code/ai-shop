"use client";

// 渠道进件的工作区操作（TDD-支付中心运营页面 §2.10 P4）：代商户创建、平台资料、蓝新资料（5 个抽屉）、附件、证照识别填资料、
// 代商户确认、提交完整版本、提交平台审核批准、退回 / 驳回、退回指定字段、送件、登记渠道结果、重试交接、蓝新资料修改，
// 以及详情里的开户交接进度、账户候选、渠道提示三段。规则在 _api/onboarding-draft.ts（可测），这里只是界面。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import { ActionForm } from "@/components/ui/action-form";
import { Skeleton } from "@/components/ui/misc";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { ActionContext, ActionSpec } from "@/components/ui/action-host";
import type { DetailSection } from "@/components/ui/detail-drawer";
import type { FieldDef } from "@/components/ui/form-drawer";
import { useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import { newebpayMeta, representativeTypes } from "@/app/payment-center/_api/newebpay-fields";
import {
  NWP_SECTIONS, PLATFORM_FIELDS, changesOf, draftCodeOf, draftEditable, onboardingActions, requiredIn, sealCodeOf, sectionFields, usesDraft, type NwpSection,
} from "@/app/payment-center/_api/onboarding-draft";
import { recognitionPatch, recognizableKeys } from "@/app/payment-center/_api/recognition";
import {
  DOCUMENT_TYPES, LEGAL_FORMS, LICENSE_SLOTS, MANUAL_RESULTS, MODIFICATION_STATUSES, SETTLE_ACCOUNT_TYPES,
  type Detail, type DocumentType, type NewebpayModification, type OnboardingDetail, type OnboardingDraft, type OnboardingFile, type OnboardingRow, type RecognizeResult,
} from "@/app/payment-center/_api/types";
import { useCopy, fill } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { fmtTime } from "@/lib/utils";
import { notify } from "@/lib/notify";
import { ONBOARDING_WS_COPY, type OnboardingWsCopy } from "./onboarding-workspace-copy";
import { DetailLoader, MiniTable, reasonField, usePc, yesNo, type C } from "./shared";

const NAME = "onboardings";
type Run = <R>(fn: () => Promise<R>, ok?: string) => Promise<R>;
type DraftRef = { operator: string; page: string; action: string; id?: string } | null;
export const useOws = () => useCopy(ONBOARDING_WS_COPY);
const tr = (o: OnboardingWsCopy, key: string, fallback: string) => (o as Record<string, string>)[key] ?? fallback;
const MiB = 1024 * 1024;

/** 敏感值先封存换 cipherRef，再一次写进草稿（near-pay：草稿里敏感字段只收 cipher:newebpay/…）。 */
async function saveDraftFields(api: PayCenterApi, no: string, draft: OnboardingDraft, changes: Record<string, string | null>) {
  const fields: Record<string, string | null> = {};
  for (const [code, v] of Object.entries(changes)) {
    const seal = sealCodeOf(code);
    fields[code] = seal && v ? (await api.onboardings.seal(no, { expectedVersion: draft.expectedVersion, fieldCode: seal, secret: v })).cipherRef : v;
  }
  return api.onboardings.saveDraft(no, { expectedVersion: draft.expectedVersion, expectedDraftVersion: draft.draftVersion, fields });
}

// ── 页面级：代商户创建进件 ──
export function useOnboardingPageActions(api: PayCenterApi, run: Run): ActionSpec<OnboardingRow>[] {
  const o = useOws();
  return [{
    key: "create", label: usePc().createOnboarding, kind: "drawer", width: "w-[560px]", perm: "pay:onboarding:manage", title: () => o.createTitle,
    render: (ctx) => <CreateOnboarding api={api} run={run} ctx={ctx} />,
  }];
}

function CreateOnboarding({ api, run, ctx }: { api: PayCenterApi; run: Run; ctx: ActionContext<OnboardingRow> }) {
  const o = useOws();
  const c = usePc();
  const { t } = useI18n();
  const [merchantNo, setMerchantNo] = React.useState("");
  const [looked, setLooked] = React.useState("");
  const [channel, setChannel] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const merchant = useQuery({ queryKey: ["pay-center", NAME, "create-merchant", api.source, looked], enabled: !!looked, retry: false, queryFn: () => api.merchants.detail(looked) });
  const m = merchant.data?.record.merchant;
  const channels = useQuery({
    queryKey: ["pay-center", NAME, "create-channels", api.source, m?.market ?? ""], enabled: !!m, retry: false,
    queryFn: () => api.channels.list({ page: 1, size: 100, market: m?.market ?? undefined }),
  });
  const usable = (channels.data?.records ?? []).filter((x) => x.channelType === "FIAT" && x.status === "ENABLED" && !x.testChannel && (!x.market || !m?.market || x.market === m.market));
  const submit = async () => {
    setBusy(true);
    try {
      const d = await run(() => api.onboardings.create({ merchantNo: looked, payChannel: channel }));
      notify.info(fill(o.created, { no: d.record.onboarding.onboardingNo }));
      ctx.close();
    } catch { /* useRun 已提示 */ } finally { setBusy(false); }
  };
  return (
    <div className="space-y-4">
      <Notice tone="info">{o.createNote}</Notice>
      <label className="block txt-body">
        <span className="mb-1 block txt-label text-muted-foreground">{o.merchantNo}</span>
        <span className="flex gap-2">
          <input className="h-9 flex-1 rounded-control bg-muted px-3" value={merchantNo} maxLength={64} onChange={(e) => setMerchantNo(e.target.value)} onBlur={() => setLooked(merchantNo.trim())} />
          <Button variant="outline" onClick={() => setLooked(merchantNo.trim())}>{o.merchantLookup}</Button>
        </span>
      </label>
      {merchant.isFetching && <Skeleton className="h-6 w-1/2" />}
      {merchant.error && <Notice tone="warning">{o.merchantNotFound}</Notice>}
      {m && (m.status !== "ACTIVE" ? <Notice tone="warning">{o.merchantInactive}</Notice> : (
        <>
          <p className="txt-body">{m.displayName} · {m.market ?? "—"}</p>
          {channels.isPending ? <Skeleton className="h-6 w-1/2" /> : usable.length === 0 ? <Notice tone="info">{o.noChannels}</Notice> : (
            <label className="block txt-body">
              <span className="mb-1 block txt-label text-muted-foreground">{o.channelPick}</span>
              <select className="h-9 w-full rounded-control bg-muted px-2" value={channel} onChange={(e) => setChannel(e.target.value)}>
                <option value="">—</option>
                {usable.map((x) => <option key={x.channelCode} value={x.channelCode}>{`${x.name} · ${x.channelCode}`}</option>)}
              </select>
            </label>
          )}
        </>
      ))}
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={ctx.close}>{t("common.cancel")}</Button>
        <Button disabled={!m || m.status !== "ACTIVE" || !channel || busy} onClick={() => void submit()}>{c.createOnboarding}</Button>
      </div>
    </div>
  );
}

// ── 行操作 ──
export function useOnboardingRowActions(api: PayCenterApi, run: Run): ActionSpec<OnboardingRow>[] {
  const o = useOws();
  const c = usePc();
  const operator = useDraftOperator();
  const allow = useCan();
  const draft = (action: string, id: string): DraftRef => (operator ? { operator, page: "pay-onboardings", action, id } : null);
  const can = (r: OnboardingRow | null, a: string) => !!r && onboardingActions(r).includes(a);
  const nwpEditable = (r: OnboardingRow | null) => !!r && usesDraft(r) && draftEditable(r);
  const sections: ActionSpec<OnboardingRow>[] = NWP_SECTIONS.map((sec) => ({
    key: `nwp-${sec}`, label: tr(o, `sec_${sec}`, sec), kind: "drawer" as const, width: "w-[640px]", perm: "pay:onboarding:manage", title: () => tr(o, `sec_${sec}`, sec),
    visible: nwpEditable,
    render: (ctx: ActionContext<OnboardingRow>) => <DraftForm api={api} row={ctx.row!} run={run} section={sec} draftRef={draft(`nwp-${sec}`, ctx.row!.onboardingNo)} onDone={ctx.back ?? ctx.close} />,
  }));
  return [
    {
      key: "platform", label: o.editPlatform, kind: "drawer", width: "w-[560px]", perm: "pay:onboarding:manage", title: () => o.editPlatformTitle, visible: nwpEditable,
      render: (ctx) => <DraftForm api={api} row={ctx.row!} run={run} section="platform" draftRef={draft("platform", ctx.row!.onboardingNo)} onDone={ctx.back ?? ctx.close} />,
    },
    ...sections,
    {
      key: "attachments", label: o.attachments, kind: "drawer", width: "w-[640px]", title: () => o.attachmentsTitle, visible: (r) => !!r && usesDraft(r),
      render: (ctx) => <AttachmentsPanel api={api} row={ctx.row!} run={run} canEdit={allow("pay:onboarding:manage") && draftEditable(ctx.row!)} />,
    },
    {
      key: "recognize", label: o.recognize, kind: "drawer", width: "w-[640px]", perm: "pay:onboarding:manage", title: () => o.recognizeTitle, visible: nwpEditable,
      render: (ctx) => <RecognizePanel api={api} row={ctx.row!} run={run} onDone={ctx.back ?? ctx.close} />,
    },
    {
      key: "confirmDraft", label: o.confirmDraft, kind: "confirm", perm: "pay:onboarding:manage", visible: nwpEditable,
      confirm: () => ({ title: o.confirmDraftTitle, desc: o.confirmDraftDesc }),
      run: async (r) => { const d = await api.onboardings.draft(r!.onboardingNo); await run(() => api.onboardings.confirmDraft(r!.onboardingNo, { expectedVersion: d.expectedVersion, expectedDraftVersion: d.draftVersion })); },
    },
    {
      key: "submitDraft", label: o.submitDraft, kind: "confirm", perm: "pay:onboarding:manage", visible: nwpEditable,
      confirm: () => ({ title: o.submitDraftTitle, desc: o.submitDraftDesc }),
      run: async (r) => { const d = await api.onboardings.draft(r!.onboardingNo); await run(() => api.onboardings.submitDraft(r!.onboardingNo, { expectedVersion: d.expectedVersion, expectedDraftVersion: d.draftVersion })); },
    },
    {
      key: "submitReview", label: o.submitReview, kind: "dialog", perm: "pay:onboarding:manage", visible: (r) => can(r, "review"),
      confirm: () => ({ title: o.submitReviewTitle, desc: o.submitReviewDesc, reasonHint: c.reasonHint }),
      run: async (r, reason) => {
        const out = await run(() => api.approvals.submit({ subjectType: "ONBOARDING_REVIEW", subjectRef: r!.onboardingNo, subjectRevision: r!.version + 1, action: "APPROVE", reason }));
        notify.info(fill(o.submittedApproval, { no: out.approvalNo }));
      },
    },
    {
      key: "review", label: c.reviewOnboarding, kind: "drawer", width: "w-[560px]", perm: "pay:onboarding:approve", title: () => o.reviewTitle, visible: (r) => can(r, "review"),
      render: (ctx) => (
        <ActionForm
          notify={false}
          fields={[{ key: "decision", label: c.decision, type: "select", required: true, options: [{ value: "REQUEST_MORE", label: o.decisionMore }, { value: "REJECT", label: o.decisionReject }] }, reasonField(c)]}
          initial={{ decision: "REQUEST_MORE", reason: "" }}
          draft={draft("review", ctx.row!.onboardingNo)}
          baseVersion={ctx.row!.version}
          onSubmit={(v) => run(() => api.onboardings.review(ctx.row!.onboardingNo, { expectedVersion: ctx.row!.version, decision: v.decision as "REJECT", reason: String(v.reason), materialVersion: ctx.row!.materialVersion }))}
          onDone={ctx.back ?? ctx.close}
        />
      ),
    },
    {
      key: "requestFields", label: o.requestFields, kind: "drawer", width: "w-[560px]", perm: "pay:onboarding:approve", title: () => o.requestFieldsTitle,
      visible: (r) => can(r, "review") && !!r && usesDraft(r),
      render: (ctx) => <RequestFieldsForm api={api} row={ctx.row!} run={run} onDone={ctx.back ?? ctx.close} />,
    },
    {
      key: "dispatch", label: c.dispatch, kind: "drawer", width: "w-[560px]", perm: "pay:onboarding:manage", title: () => c.dispatchTitle, visible: (r) => can(r, "dispatch"),
      render: (ctx) => {
        const manual = ctx.row!.submitMode === "MANUAL";
        return (
          <>
            {!manual && <Notice tone="info" className="mb-4">{o.dispatchApi}</Notice>}
            <ActionForm
              notify={false}
              fields={[...(manual ? [{ key: "channelApplyNo", label: c.channelApplyNo, required: true, maxLength: 64 } as FieldDef] : []), reasonField(c)]}
              initial={{ channelApplyNo: "", reason: "" }}
              draft={draft("dispatch", ctx.row!.onboardingNo)}
              baseVersion={ctx.row!.version}
              onSubmit={(v) => run(() => api.onboardings.dispatch(ctx.row!.onboardingNo, { expectedVersion: ctx.row!.version, channelApplyNo: manual ? String(v.channelApplyNo) : null, reason: String(v.reason) }))}
              onDone={ctx.back ?? ctx.close}
            />
          </>
        );
      },
    },
    {
      key: "manualResult", label: o.manualResult, kind: "drawer", width: "w-[560px]", perm: "pay:onboarding:approve", title: () => o.manualResultTitle, visible: (r) => can(r, "manualResult"),
      render: (ctx) => (
        <ActionForm
          notify={false}
          fields={[
            { key: "result", label: o.result, type: "select", required: true, options: MANUAL_RESULTS.map((x) => ({ value: x, label: tr(o, `res_${x}`, x) })) },
            { key: "signUrl", label: o.signUrl, maxLength: 512, help: o.signUrlHelp },
            { key: "channelMerchantId", label: o.channelMerchantId, maxLength: 64, help: o.channelMerchantIdHelp },
            { key: "subMerchantId", label: o.subMerchantId, maxLength: 64 },
            { key: "evidenceRef", label: o.evidenceRef, maxLength: 255, help: o.evidenceRefHelp },
            reasonField(c),
          ]}
          initial={{ result: "APPROVED", signUrl: "", channelMerchantId: "", subMerchantId: "", evidenceRef: "", reason: "" }}
          draft={draft("manualResult", ctx.row!.onboardingNo)}
          baseVersion={ctx.row!.version}
          onSubmit={(v) => {
            const s = (k: string) => String((v as Record<string, unknown>)[k] ?? "").trim();
            if (v.result === "NEED_SIGN" && !/^https:\/\//.test(s("signUrl"))) { notify.error(o.needSignUrl); return Promise.reject(new Error(o.needSignUrl)); }
            if (v.result === "APPROVED" && (!s("channelMerchantId") || !/^file:/.test(s("evidenceRef")))) { notify.error(o.needApprovedFields); return Promise.reject(new Error(o.needApprovedFields)); }
            return run(() => api.onboardings.manualResult(ctx.row!.onboardingNo, {
              expectedVersion: ctx.row!.version, result: String(v.result), reason: s("reason"),
              signUrl: v.result === "NEED_SIGN" ? s("signUrl") : null,
              channelMerchantId: v.result === "APPROVED" ? s("channelMerchantId") : null,
              subMerchantId: v.result === "APPROVED" ? s("subMerchantId") || null : null,
              evidenceRef: v.result === "APPROVED" ? s("evidenceRef") : null,
            }));
          }}
          onDone={ctx.back ?? ctx.close}
        />
      ),
    },
    {
      key: "retryHandoff", label: o.retryHandoff, kind: "dialog", perm: "pay:onboarding:manage", visible: (r) => can(r, "retryHandoff") && !!r && r.candidateStatus === "PENDING",
      confirm: () => ({ title: o.retryHandoffTitle, desc: o.retryHandoffDesc, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.onboardings.retryHandoff(r!.onboardingNo, { expectedVersion: r!.version, reason })),
    },
    {
      key: "modifications", label: o.modifications, kind: "drawer", width: "w-[720px]", title: () => o.modificationsTitle,
      visible: (r) => !!r && r.status === "APPROVED" && usesDraft(r),
      render: (ctx) => <ModificationsPanel api={api} row={ctx.row!} run={run} onNew={() => ctx.switchTo("newModification")} />,
    },
    {
      key: "newModification", label: o.newModification, kind: "drawer", width: "w-[640px]", perm: "pay:onboarding:manage", title: () => o.newModificationTitle,
      visible: (r) => !!r && r.status === "APPROVED" && usesDraft(r),
      render: (ctx) => <NewModificationForm api={api} row={ctx.row!} run={run} onDone={ctx.back ?? ctx.close} />,
    },
  ];
}

/** 各操作按状态是否可见：交给原有的「提交材料（非蓝新）/ 刷新 / 取消」也用同一张表 */
export const onboardingCan = (r: OnboardingRow | null, a: string) => !!r && onboardingActions(r).includes(a);

// ── 详情追加段 ──
export function onboardingExtraSections(api: PayCenterApi, d: Detail<OnboardingDetail>, c: C, o: OnboardingWsCopy): DetailSection[] {
  const row = d.record.onboarding;
  const out: DetailSection[] = [];
  const lastResult = d.record.versions[0]?.resultCode ?? null;
  const warn = [lastResult === "AMR10014" ? o.warnAmr10014 : null, lastResult === "KEY_RECEIPT_MISSING" ? o.warnReceipt : null].filter(Boolean) as string[];
  if (warn.length) out.push({ title: o.sectionWarnings, content: <>{warn.map((w) => <Notice key={w} tone="warning" className="mb-2">{w}</Notice>)}</> });
  if (row.signUrl) out.push({ title: o.signUrl, content: <a href={row.signUrl} target="_blank" rel="noreferrer noopener" className="txt-body text-primary-ink hover:underline">{o.openSign}</a> });
  if (usesDraft(row)) {
    out.push({ title: o.draftState, content: <DraftSummary api={api} row={row} /> });
    out.push({ title: o.sectionReadiness, content: (
      <DetailLoader name={NAME} id={`${row.onboardingNo}:readiness`} load={() => api.onboardings.readiness(row.onboardingNo)}>
        {(r) => (
          <MiniTable<[string, string]> rowKey={(x) => x[0]} empty={o.emptyReadiness} rows={[
            [o.receiptStage, r.receiptStage], [o.candidateStage, r.candidateStage ?? "—"], [o.accountStatus, `${r.payMerchantNo ?? "—"} · ${r.accountStatus ?? "—"}`],
            [o.verificationStatus, r.verificationStatus ?? "—"], [o.credentialStatus, r.credentialStatus ?? "—"], [o.openProducts, String(r.openProductCount)], [o.channelReady, yesNo(r.channelReady, c)],
          ]} columns={[{ header: "", cell: (x) => x[0] }, { header: "", cell: (x) => x[1] }]} />
        )}
      </DetailLoader>
    ) });
  }
  if (d.record.candidate) {
    const k = d.record.candidate;
    out.push({ title: o.sectionCandidate, fields: [
      { label: o.channelMerchantId, value: k.channelMerchantId }, { label: o.subMerchantId, value: k.subMerchantId ?? "—" },
      { label: o.candidateOrigin, value: k.origin }, { label: c.colStatus, value: k.status }, { label: o.evidenceRef, value: k.evidenceRef ?? "—", full: true },
    ] });
  }
  return out;
}

function DraftSummary({ api, row }: { api: PayCenterApi; row: OnboardingRow }) {
  const o = useOws();
  const map: StatusMap<"EDITING" | "CONFIRMED" | "FROZEN"> = {
    EDITING: { label: o.st_draft_EDITING, tone: "info" }, CONFIRMED: { label: o.st_draft_CONFIRMED, tone: "success" }, FROZEN: { label: o.st_draft_FROZEN, tone: "muted" },
  };
  return (
    <DetailLoader name={NAME} id={`${row.onboardingNo}:draft`} load={() => api.onboardings.draft(row.onboardingNo)}>
      {(d) => (
        <div className="space-y-2 txt-body">
          <StatusBadge value={d.state} map={map} />
          {d.supplementFields.length > 0 && <Notice tone="warning">{fill(o.draftSupplement, { fields: d.supplementFields.join("、"), reason: d.supplementReason ?? "—" })}</Notice>}
          {d.confirmedBy && <p className="text-muted-foreground">{fill(o.draftConfirmedBy, { who: d.confirmedBy })}</p>}
          {d.missing.length > 0 && <p className="text-muted-foreground">{fill(o.draftMissing, { fields: d.missing.map((x) => fieldLabel(o, x)).join("、") })}</p>}
          {(d.channelMissing ?? []).length > 0 && <p className="text-muted-foreground">{fill(o.draftChannelMissing, { fields: (d.channelMissing ?? []).map((x) => fieldLabel(o, x)).join("、") })}</p>}
        </div>
      )}
    </DetailLoader>
  );
}

const fieldLabel = (o: OnboardingWsCopy, code: string) => {
  if (code.startsWith("NWP_")) return newebpayMeta[code.slice(4)]?.label ?? code;
  if (code.startsWith("LICENSE_")) return `${o.attachments} ${code.slice(8)}`;
  return tr(o, `f_${code}`, code);
};

// ── 资料表单（平台 / 蓝新各段）──
function DraftForm({ api, row, run, section, draftRef, onDone }: { api: PayCenterApi; row: OnboardingRow; run: Run; section: "platform" | NwpSection; draftRef: DraftRef; onDone: () => void }) {
  const o = useOws();
  return (
    <DetailLoader name={NAME} id={`${row.onboardingNo}:draft`} load={() => api.onboardings.draft(row.onboardingNo)}>
      {(d) => {
        if (!d.editable) return <Notice tone="info">{o.draftFrozen}</Notice>;
        if (section === "platform") return <PlatformForm api={api} row={row} run={run} d={d} draftRef={draftRef} onDone={onDone} />;
        return <NwpForm api={api} row={row} run={run} d={d} section={section} draftRef={draftRef} onDone={onDone} />;
      }}
    </DetailLoader>
  );
}

function sensitiveHelp(o: OnboardingWsCopy, d: OnboardingDraft, code: string) {
  return d.filledFields.includes(code) ? o.sensitiveKept : undefined;
}

function PlatformForm({ api, row, run, d, draftRef, onDone }: { api: PayCenterApi; row: OnboardingRow; run: Run; d: OnboardingDraft; draftRef: DraftRef; onDone: () => void }) {
  const o = useOws();
  const fields: FieldDef[] = [
    { key: "LEGAL_FORM", label: o.f_LEGAL_FORM, type: "select", required: true, options: [{ value: "", label: "—" }, ...LEGAL_FORMS.map((x) => ({ value: x, label: tr(o, `lf_${x}`, x) }))] },
    { key: "CONTACT_NAME", label: o.f_CONTACT_NAME, required: true, maxLength: 64 },
    { key: "CONTACT_PHONE", label: o.f_CONTACT_PHONE, type: "password", sensitive: true, maxLength: 32, help: sensitiveHelp(o, d, "CONTACT_PHONE") },
    { key: "SETTLE_ACCOUNT_TYPE", label: o.f_SETTLE_ACCOUNT_TYPE, type: "select", required: true, options: [{ value: "", label: "—" }, ...SETTLE_ACCOUNT_TYPES.map((x) => ({ value: x, label: tr(o, `sat_${x}`, x) }))] },
    { key: "SETTLE_ACCOUNT", label: o.f_SETTLE_ACCOUNT, type: "password", sensitive: true, maxLength: 64, help: sensitiveHelp(o, d, "SETTLE_ACCOUNT") },
  ];
  return <DraftActionForm api={api} row={row} run={run} d={d} fields={fields} codes={PLATFORM_FIELDS} draftRef={draftRef} onDone={onDone} />;
}

function NwpForm({ api, row, run, d, section, draftRef, onDone }: { api: PayCenterApi; row: OnboardingRow; run: Run; d: OnboardingDraft; section: NwpSection; draftRef: DraftRef; onDone: () => void }) {
  const o = useOws();
  const legal = d.fields.LEGAL_FORM;
  const [repType, setRepType] = React.useState<string>(d.representativeType ?? "");
  if (!legal) return <Notice tone="info">{o.needLegalForm}</Notice>;
  const needType = section === "identity" && legal === "COMPANY";
  const metas = sectionFields(section, legal, needType ? repType || null : d.representativeType ?? null);
  const fields: FieldDef[] = metas.map((m) => {
    const code = draftCodeOf(m.code);
    const opts = m.options && Object.keys(m.options).length ? [{ value: "", label: "—" }, ...Object.entries(m.options).map(([value, label]) => ({ value, label: `${value} · ${label}` }))] : undefined;
    return {
      key: code, label: m.label, maxLength: m.code === "MerchantDesc" ? 1024 : m.maxLength,
      required: !m.sensitive && requiredIn(m, legal, repType || d.representativeType),
      // 有选项的用下拉（敏感的照样不进草稿、保存前封存）；没选项的敏感值用掩码输入
      ...(opts ? { type: "select" as const, options: opts } : m.sensitive ? { type: "password" as const } : {}),
      ...(m.sensitive ? { sensitive: true, help: sensitiveHelp(o, d, code) } : {}),
      ...(m.pattern && !m.sensitive ? { pattern: { re: `^${m.pattern}$`, msg: m.label } } : {}),
    };
  });
  return (
    <>
      {needType && (
        <label className="mb-4 block txt-body">
          <span className="mb-1 block txt-label text-muted-foreground">{o.representativeType}</span>
          <select className="h-9 w-full rounded-control bg-muted px-2" value={repType} onChange={(e) => setRepType(e.target.value)}>
            <option value="">—</option>
            {Object.entries(representativeTypes).map(([k, v]) => <option key={k} value={k}>{`${k} · ${v}`}</option>)}
          </select>
          {!repType && <span className="mt-1 block txt-caption text-muted-foreground">{o.representativePick}</span>}
        </label>
      )}
      {(!needType || repType) && (
        <DraftActionForm key={repType} api={api} row={row} run={run} d={d} fields={fields} codes={fields.map((f) => f.key)} representativeType={repType || null} draftRef={draftRef} onDone={onDone} />
      )}
    </>
  );
}

function DraftActionForm({ api, row, run, d, fields, codes, representativeType, draftRef, onDone }: {
  api: PayCenterApi; row: OnboardingRow; run: Run; d: OnboardingDraft; fields: FieldDef[]; codes: readonly string[]; representativeType?: string | null; draftRef: DraftRef; onDone: () => void;
}) {
  const o = useOws();
  const initial = Object.fromEntries(codes.map((k) => [k, sealCodeOf(k) ? "" : d.fields[k] ?? ""]));
  return (
    <ActionForm
      notify={false}
      fields={fields}
      initial={initial}
      draft={draftRef}
      baseVersion={d.draftVersion}
      onSubmit={(v) => {
        const changes = changesOf(d, v, codes, representativeType);
        if (!Object.keys(changes).length) { notify.info(o.noChanges); return Promise.resolve(); }
        return run(() => saveDraftFields(api, row.onboardingNo, d, changes), o.saved);
      }}
      onDone={onDone}
    />
  );
}

// ── 附件 ──
function AttachmentsPanel({ api, row, run, canEdit }: { api: PayCenterApi; row: OnboardingRow; run: Run; canEdit: boolean }) {
  const o = useOws();
  const files = useQuery({ queryKey: ["pay-center", NAME, "files", api.source, row.onboardingNo], queryFn: () => api.onboardings.files(row.onboardingNo) });
  const draft = useQuery({ queryKey: ["pay-center", NAME, "detail", api.source, `${row.onboardingNo}:draft`], queryFn: () => api.onboardings.draft(row.onboardingNo) });
  const [preview, setPreview] = React.useState<{ slot: string; url: string; type: string } | null>(null);
  const urlRef = React.useRef<string | null>(null);
  const release = () => { if (urlRef.current) URL.revokeObjectURL(urlRef.current); urlRef.current = null; };
  React.useEffect(() => release, []);
  const inputs = React.useRef<Record<string, HTMLInputElement | null>>({});
  const bySlot = new Map((files.data ?? []).map((f) => [f.slot, f]));
  const upload = async (slot: string, file: File) => {
    if (file.size > 5 * MiB) { notify.error(o.fileTooBig); return; }
    if (!/\.(png|jpe?g|pdf)$/i.test(file.name)) { notify.error(o.fileBadType); return; }
    const d = draft.data ?? await api.onboardings.draft(row.onboardingNo);
    try {
      await run(() => api.onboardings.upload(row.onboardingNo, { slot, expectedVersion: d.expectedVersion, expectedDraftVersion: d.draftVersion }, file), fill(o.uploaded, { slot }));
      await Promise.all([files.refetch(), draft.refetch()]);
    } catch { /* 已提示 */ }
  };
  const view = async (f: OnboardingFile) => {
    try {
      const blob = await api.onboardings.file(row.onboardingNo, f.fileNo!);
      release(); urlRef.current = URL.createObjectURL(blob);
      setPreview({ slot: f.slot, url: urlRef.current, type: blob.type || f.contentType || "" });
    } catch (e) { notify.error(e instanceof Error ? e.message : String(e)); }
  };
  if (files.isPending) return <Skeleton className="h-24 w-full" />;
  if (files.error) return <Notice tone="danger">{String((files.error as Error).message)}</Notice>;
  const state = (f?: OnboardingFile) => !f ? o.fileEmpty : f.legacy ? o.fileLegacy : f.expired ? o.fileExpired : fill(o.fileReady, { size: `${Math.max(1, Math.round((f.byteLength ?? 0) / 1024))} KB` });
  return (
    <div className="space-y-4">
      <Notice tone="info">{o.attachmentsNote}</Notice>
      <MiniTable<string> rows={[...LICENSE_SLOTS]} rowKey={(s) => s} empty={o.attachmentsNote} columns={[
        { header: o.slot, cell: (s) => s },
        { header: o.fileState, cell: (s) => state(bySlot.get(s)) },
        { header: "", cell: (s) => {
          const f = bySlot.get(s);
          return (
            <span className="flex gap-2">
              {f?.fileNo && !f.expired && <Button size="sm" variant="outline" onClick={() => void view(f)}>{o.view}</Button>}
              {canEdit && (
                <>
                  <input ref={(el) => { inputs.current[s] = el; }} type="file" accept="image/png,image/jpeg,application/pdf" className="hidden"
                    onChange={(e) => { const file = e.target.files?.[0]; e.target.value = ""; if (file) void upload(s, file); }} />
                  <Button size="sm" variant="outline" onClick={() => inputs.current[s]?.click()}>{f ? o.replace : o.upload}</Button>
                </>
              )}
            </span>
          );
        } },
      ]} />
      {preview && (
        <section>
          <div className="mb-2 flex items-center justify-between"><span className="txt-label text-muted-foreground">{preview.slot}</span><Button size="sm" variant="ghost" onClick={() => { release(); setPreview(null); }}>{o.closePreview}</Button></div>
          {preview.type.startsWith("image/") ? <img src={preview.url} alt={preview.slot} className="max-h-96 rounded-card" /> : <a href={preview.url} download={`${preview.slot}.pdf`} className="txt-body text-primary-ink hover:underline">PDF</a>}
        </section>
      )}
    </div>
  );
}

// ── 证照识别填资料 ──
function RecognizePanel({ api, row, run, onDone }: { api: PayCenterApi; row: OnboardingRow; run: Run; onDone: () => void }) {
  const o = useOws();
  const [type, setType] = React.useState<DocumentType>("REGISTRATION");
  const [file, setFile] = React.useState<File | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<RecognizeResult | null>(null);
  const [picked, setPicked] = React.useState<string[]>([]);
  const draft = useQuery({ queryKey: ["pay-center", NAME, "detail", api.source, `${row.onboardingNo}:draft`], queryFn: () => api.onboardings.draft(row.onboardingNo) });
  const recognize = async () => {
    if (!file) return;
    if (file.size > 12 * MiB) { notify.error(o.fileTooBigOcr); return; }
    setBusy(true); setResult(null);
    try { const r = await api.documents.recognize(file, type); setResult(r); setPicked(recognizableKeys(r)); }
    catch (e) { notify.error(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };
  const write = async () => {
    const d = draft.data;
    if (!d || !result) return;
    const patch = recognitionPatch(result, picked, d.fields.LEGAL_FORM, d.representativeType);
    if (!Object.keys(patch).length) { notify.error(o.nothingPicked); return; }
    try { await run(() => saveDraftFields(api, row.onboardingNo, d, patch), o.saved); onDone(); } catch { /* 已提示 */ }
  };
  const keys = result ? recognizableKeys(result) : [];
  return (
    <div className="space-y-4">
      <Notice tone="info">{o.recognizeNote}</Notice>
      {!draft.data?.fields.LEGAL_FORM && draft.data && <Notice tone="warning">{o.needLegalForm}</Notice>}
      <label className="block txt-body">
        <span className="mb-1 block txt-label text-muted-foreground">{o.docType}</span>
        <select className="h-9 w-full rounded-control bg-muted px-2" value={type} onChange={(e) => { setType(e.target.value as DocumentType); setResult(null); }}>
          {DOCUMENT_TYPES.map((x) => <option key={x} value={x}>{tr(o, `doc_${x}`, x)}</option>)}
        </select>
      </label>
      <label className="block txt-body">
        <span className="mb-1 block txt-label text-muted-foreground">{o.docFile}</span>
        <input type="file" accept="image/png,image/jpeg,application/pdf" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); }} />
      </label>
      <Button disabled={!file || busy} onClick={() => void recognize()}>{busy ? o.recognizing : o.runRecognize}</Button>
      {result && !result.recognized && <Notice tone="info">{o.notRecognized}</Notice>}
      {result?.recognized && (
        <section className="space-y-2">
          <p className="txt-label text-muted-foreground">{o.pickFields}</p>
          {keys.map((k) => (
            <label key={k} className="flex items-center gap-2 txt-body">
              <input type="checkbox" checked={picked.includes(k)} onChange={(e) => setPicked((p) => (e.target.checked ? [...p, k] : p.filter((x) => x !== k)))} />
              <span className="w-28 text-muted-foreground">{k}</span><span>{result.fields[k]}</span>
            </label>
          ))}
          <div className="flex justify-end"><Button disabled={!picked.length} onClick={() => void write()}>{o.writeDraft}</Button></div>
        </section>
      )}
    </div>
  );
}

// ── 退回指定字段补件 ──
function RequestFieldsForm({ api, row, run, onDone }: { api: PayCenterApi; row: OnboardingRow; run: Run; onDone: () => void }) {
  const o = useOws();
  const options = [
    ...PLATFORM_FIELDS.map((k) => ({ value: k, label: fieldLabel(o, k) })),
    ...Object.values(newebpayMeta).filter((m) => m.owner === "MERCHANT").map((m) => ({ value: draftCodeOf(m.code), label: m.label })),
    ...LICENSE_SLOTS.map((k) => ({ value: k, label: k })),
  ];
  return (
    <DetailLoader name={NAME} id={`${row.onboardingNo}:draft`} load={() => api.onboardings.draft(row.onboardingNo)}>
      {(d) => (
        <ActionForm
          notify={false}
          fields={[
            { key: "fields", label: o.requestFieldsPick, type: "multiselect", csv: true, required: true, options },
            { key: "reason", label: o.requestFieldsReason, type: "textarea", required: true, maxLength: 255, rows: 2 },
          ]}
          initial={{ fields: "", reason: "" }}
          draft={null}
          onSubmit={(v) => {
            const fields = String(v.fields ?? "").split(",").filter(Boolean);
            if (fields.length < 1 || fields.length > 49) { notify.error(o.requestFieldsNeedOne); return Promise.reject(new Error(o.requestFieldsNeedOne)); }
            return run(() => api.onboardings.requestMoreFields(row.onboardingNo, { expectedVersion: d.expectedVersion, expectedDraftVersion: d.draftVersion, fields, reason: String(v.reason) }));
          }}
          onDone={onDone}
        />
      )}
    </DetailLoader>
  );
}

// ── 蓝新资料修改 ──
function ModificationsPanel({ api, row, run, onNew }: { api: PayCenterApi; row: OnboardingRow; run: Run; onNew: () => void }) {
  const o = useOws();
  const c = usePc();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const q = useQuery({ queryKey: ["pay-center", NAME, "modifications", api.source, row.onboardingNo], queryFn: () => api.onboardings.modifications(row.onboardingNo), retry: false });
  const statusMap = Object.fromEntries(MODIFICATION_STATUSES.map((s) => [s, { label: tr(o, `st_mod_${s}`, s), tone: s === "ACCEPTED" ? "success" as const : s === "REJECTED" ? "danger" as const : s === "UNKNOWN" ? "warning" as const : "muted" as const }]));
  if (q.isPending) return <Skeleton className="h-24 w-full" />;
  if (q.error) return <Notice tone="info">{o.modificationsUnavailable}</Notice>;
  const busy = q.data!.requests.some((r) => ["PENDING", "APPROVED", "SENDING", "UNKNOWN"].includes(r.status));
  const act = (title: string, fn: (reason: string) => Promise<unknown>) => void confirm({ title, requireReason: true, reasonHint: c.reasonHint, action: async (reason) => { await run(() => fn(reason)); await q.refetch(); } });
  return (
    <div className="space-y-4">
      <Notice tone="info">{o.modificationsNote}</Notice>
      <MiniTable<NewebpayModification> rows={q.data!.requests} rowKey={(r) => r.changeNo} empty={o.emptyModifications} columns={[
        { header: o.changeNo, cell: (r) => r.changeNo },
        { header: o.changedFields, cell: (r) => Object.entries(r.maskedChanges).map(([k, v]) => `${newebpayMeta[k]?.label ?? k}：${v}`).join("；") },
        { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
        { header: "", cell: (r) => allow("pay:onboarding:manage") && (
          <span className="flex gap-2">
            {r.status === "PENDING" && <Button size="sm" variant="outline" onClick={() => act(o.submitModTitle, (reason) => api.approvals.submit({ subjectType: "NEWEBPAY_MERCHANT_MODIFY", subjectRef: r.changeNo, subjectRevision: r.version + 1, action: "APPROVE", reason }))}>{o.submitApproval}</Button>}
            {r.status === "APPROVED" && <Button size="sm" variant="outline" onClick={() => act(o.sendTitle, (reason) => api.onboardings.dispatchModification(row.onboardingNo, r.changeNo, { expectedVersion: r.version, reason }))}>{o.send}</Button>}
            {["PENDING", "APPROVED"].includes(r.status) && <Button size="sm" variant="ghost" onClick={() => act(o.cancelChangeTitle, (reason) => api.onboardings.cancelModification(row.onboardingNo, r.changeNo, { expectedVersion: r.version, reason }))}>{o.cancelChange}</Button>}
          </span>
        ) },
      ]} />
      {allow("pay:onboarding:manage") && (busy ? <Notice tone="info">{o.modificationBusy}</Notice> : <div className="flex justify-end"><Button onClick={onNew}>{o.newModification}</Button></div>)}
      {dialog}
    </div>
  );
}

function NewModificationForm({ api, row, run, onDone }: { api: PayCenterApi; row: OnboardingRow; run: Run; onDone: () => void }) {
  const o = useOws();
  return (
    <DetailLoader name={NAME} id={`${row.onboardingNo}:modifications`} load={() => api.onboardings.modifications(row.onboardingNo)}>
      {(view) => {
        const applicant = new Set(["ApplicantName", "ApplicantEmail", "ApplicantPhone"]);
        const labelOf: Record<string, string> = { ApplicantName: o.applicantName, ApplicantEmail: o.applicantEmail, ApplicantPhone: o.applicantPhone };
        const fields: FieldDef[] = view.fields.map((f) => ({
          key: f.code, label: labelOf[f.code] ?? f.label, maxLength: f.maxLength, required: applicant.has(f.code),
          ...(f.options && Object.keys(f.options).length ? { type: "select" as const, options: [{ value: "", label: "—" }, ...Object.entries(f.options).map(([value, label]) => ({ value, label }))] } : {}),
          ...(newebpayMeta[f.code]?.sensitive ? { sensitive: true } : {}),
        }));
        return (
          <>
            <Notice tone="info" className="mb-4">{o.newModificationNote}</Notice>
            <ActionForm
              notify={false}
              fields={fields}
              initial={Object.fromEntries(view.fields.map((f) => [f.code, ""]))}
              draft={null}
              onSubmit={async (v) => {
                const changes = Object.fromEntries(Object.entries(v).map(([k, x]) => [k, String(x ?? "").trim()]).filter(([k, x]) => x !== "" && !applicant.has(k)));
                if (!Object.keys(changes).length) { notify.error(o.nothingChanged); throw new Error(o.nothingChanged); }
                const all = Object.fromEntries(Object.entries(v).map(([k, x]) => [k, String(x ?? "").trim()]).filter(([, x]) => x !== ""));
                const detail = await api.onboardings.detail(row.onboardingNo);
                return run(() => api.onboardings.createModification(row.onboardingNo, { expectedVersion: Number(detail.version ?? row.version), changes: all }));
              }}
              onDone={onDone}
            />
          </>
        );
      }}
    </DetailLoader>
  );
}

