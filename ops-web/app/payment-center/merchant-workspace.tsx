"use client";

// 商户档案的工作区操作（TDD-支付中心运营页面 §2.10 P4）：新建 / 测试商户、代填资料、收款策略、核验来源、一键确认、
// 提交恢复审批、主体资料核对与退回补件，以及详情里的能力进度、渠道、默认策略、来源事件四段。
// 每个抽屉一件事；需要审批的只「提交审批」，批驳在审核待办 / 商户变更。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { StatusBadge } from "@/components/ui/status-badge";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import { ActionForm } from "@/components/ui/action-form";
import { Skeleton } from "@/components/ui/misc";
import type { ActionSpec } from "@/components/ui/action-host";
import type { DetailSection } from "@/components/ui/detail-drawer";
import { useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import { compareRecognition, recognitionTypeOf, sniffEvidence, type ComparisonRow } from "@/app/payment-center/_api/recognition";
import {
  ASSISTED_FIELDS, type AssistedField, type CapabilityItem, type EntityMaterials, type MerchantChannelRow, type MerchantDetail, type MerchantRow,
  type RecognizeResult, type SourceEvent,
} from "@/app/payment-center/_api/types";
import { useCopy, fill } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { fmtTime } from "@/lib/utils";
import { notify } from "@/lib/notify";
import { MERCHANT_WS_COPY, type MerchantWsCopy } from "./merchant-workspace-copy";
import { DetailLoader, MiniTable, joinCsv, reasonField, usePc, yesNo, type C } from "./shared";

const NAME = "merchants";
/** 代填资料里服务端只回脱敏值的三项：不进本地草稿 */
const SENSITIVE: readonly AssistedField[] = ["registrationNo", "representativeName", "contactPhone"];
const MAX: Record<AssistedField, number> = { legalName: 128, registrationNo: 64, registeredAddress: 255, representativeName: 64, contactPhone: 32, industry: 64 };

export const useMws = () => useCopy(MERCHANT_WS_COPY);

/** 页面级：新建支付商户 / 测试商户。可用与否由 near-pay 的 context 接口说了算（关着时整个按钮不出现）。 */
export function useMerchantPageActions(api: PayCenterApi, run: <R>(fn: () => Promise<R>, ok?: string) => Promise<R>): ActionSpec<MerchantRow>[] {
  const m = useMws();
  const operator = useDraftOperator();
  const allow = useCan();
  const canManage = allow("pay:merchant:manage");
  const creation = useQuery({ queryKey: ["pay-center", NAME, "creation-context", api.source], queryFn: () => api.merchants.creationContext(), enabled: canManage, retry: false, staleTime: 5 * 60_000 });
  const test = useQuery({ queryKey: ["pay-center", NAME, "test-context", api.source], queryFn: () => api.merchants.testContext(), enabled: canManage, retry: false, staleTime: 5 * 60_000 });
  const draft = (action: string) => (operator ? { operator, page: "pay-merchants", action } : null);
  return [
    {
      key: "create", label: m.createMerchant, kind: "drawer", perm: "pay:merchant:manage", title: () => m.createMerchantTitle,
      visible: () => creation.data?.enabled === true,
      pending: () => canManage && creation.isPending,
      render: (ctx) => (
        <>
          <Notice tone="info" className="mb-4">{m.createMerchantNote}</Notice>
          <ActionForm
            notify={false}
            fields={[
              { key: "operatingEntityId", label: m.operatingEntityId, required: true, maxLength: 64, pattern: { re: "^[A-Za-z0-9_-]{1,64}$", msg: m.operatingEntityHelp }, help: m.operatingEntityHelp },
              { key: "name", label: m.merchantDisplayName, required: true, maxLength: 100 },
            ]}
            initial={{ operatingEntityId: "", name: "" }}
            draft={draft("create")}
            onSubmit={async (v) => {
              const r = await run(() => api.merchants.create({ operatingEntityId: String(v.operatingEntityId).trim(), name: String(v.name).trim() }));
              notify.info(fill(m.createdMerchant, { no: r.merchantNo }));
            }}
            onDone={ctx.close}
          />
        </>
      ),
    },
    {
      key: "createTest", label: m.createTest, kind: "drawer", perm: "pay:merchant:manage", title: () => m.createTestTitle,
      visible: () => test.data?.enabled === true,
      pending: () => canManage && test.isPending,
      render: (ctx) => (
        <>
          <Notice tone="warning" className="mb-4">{m.createTestNote}</Notice>
          <ActionForm
            notify={false}
            fields={[{ key: "batchNo", label: m.batchNo, required: true, maxLength: 48, pattern: { re: "^[A-Za-z0-9_-]{1,48}$", msg: m.batchNoHelp }, help: m.batchNoHelp }]}
            initial={{ batchNo: "" }}
            draft={draft("createTest")}
            onSubmit={async (v) => {
              const r = await run(() => api.merchants.createTest({ batchNo: String(v.batchNo).trim() }));
              notify.info(fill(m.createdTest, { no: r.merchantNo }));
            }}
            onDone={ctx.close}
          />
        </>
      ),
    },
  ];
}

/** 行操作（详情底栏同样出现）。 */
export function useMerchantRowActions(api: PayCenterApi, run: <R>(fn: () => Promise<R>, ok?: string) => Promise<R>): ActionSpec<MerchantRow>[] {
  const m = useMws();
  const c = usePc();
  const operator = useDraftOperator();
  const allow = useCan();
  const draft = (action: string, id: string) => (operator ? { operator, page: "pay-merchants", action, id } : null);
  return [
    {
      key: "assist", label: m.assist, kind: "drawer", width: "w-[560px]", perm: "pay:merchant:manage", title: () => m.assistTitle,
      visible: (r) => !!r && !r.sourceSystem && r.status === "ACTIVE",
      render: (ctx) => <AssistForm api={api} row={ctx.row!} run={run} draft={draft("assist", ctx.row!.merchantNo)} onDone={ctx.back ?? ctx.close} />,
    },
    {
      key: "submitAssist", label: m.submitAssist, kind: "confirm", perm: "pay:merchant:manage",
      visible: (r) => !!r && !r.sourceSystem && r.status === "ACTIVE",
      confirm: () => ({ title: m.submitAssistTitle, desc: m.submitAssistDesc }),
      run: async (r) => {
        const d = await api.merchants.assistedDraft(r!.merchantNo);
        if (d.version === 0) { notify.error(m.assistNoDraft); throw new Error(m.assistNoDraft); }
        const out = await run(() => api.merchants.submitAssisted(r!.merchantNo, { expectedVersion: d.version }));
        notify.info(fill(m.submittedAssist, { rev: out.revisionNo ?? "—" }));
      },
    },
    {
      key: "policy", label: m.editPolicy, kind: "drawer", width: "w-[560px]", perm: "pay:collection-policy:manage", title: () => m.editPolicyTitle,
      render: (ctx) => <PolicyForm api={api} row={ctx.row!} run={run} draft={draft("policy", ctx.row!.merchantNo)} onDone={ctx.back ?? ctx.close} />,
    },
    {
      key: "verifyLink", label: m.verifyLink, kind: "drawer", width: "w-[560px]", perm: "pay:merchant:manage", title: () => m.verifyLinkTitle,
      visible: (r) => !!r && !!r.sourceSystem,
      render: (ctx) => <VerifyLinkForm api={api} row={ctx.row!} run={run} draft={draft("verifyLink", ctx.row!.merchantNo)} onDone={ctx.back ?? ctx.close} />,
    },
    {
      key: "confirm", label: m.confirmAll, kind: "confirm", perm: "pay:merchant:approve",
      visible: (r) => !!r && allow("pay:merchant:manage") && (r.profileStatus === "SUBMITTED" || r.verifyStatus === "PENDING"),
      confirm: () => ({ title: m.confirmAllTitle, desc: m.confirmAllDesc }),
      run: async (r) => {
        const out = await run(() => api.merchants.confirm(r!.merchantNo, {}));
        notify.info(fill(m.confirmedAll, { links: out.verifiedLinks.length, revs: out.approvedRevisions.length }));
      },
    },
    {
      key: "submitResume", label: m.submitResume, kind: "dialog", perm: "pay:approval:view", visible: (r) => r?.status === "SUSPENDED",
      confirm: () => ({ title: m.submitResumeTitle, desc: m.submitResumeDesc, reasonHint: c.reasonHint }),
      run: async (r, reason) => {
        const out = await run(() => api.approvals.submit({ subjectType: "PLATFORM_MERCHANT_RESUME", subjectRef: r!.merchantNo, subjectRevision: r!.version + 1, action: "RESUME", reason }));
        notify.info(fill(m.submittedApproval, { no: out.approvalNo }));
      },
    },
    {
      key: "entity", label: m.entity, kind: "drawer", width: "w-[760px]", perm: "pay:merchant:approve", title: () => m.entityTitle,
      render: (ctx) => <EntityPanel api={api} row={ctx.row!} />,
    },
    {
      key: "entityMore", label: m.entityMore, kind: "drawer", width: "w-[560px]", perm: "pay:merchant:approve", title: () => m.entityMoreTitle,
      visible: (r) => r?.profileStatus === "SUBMITTED",
      render: (ctx) => <EntityMoreForm api={api} row={ctx.row!} run={run} onDone={ctx.back ?? ctx.close} />,
    },
  ];
}

/** 详情抽屉里追加的四段：每段各自取数，失败不影响基本信息。 */
export function merchantExtraSections(api: PayCenterApi, no: string, c: C, m: MerchantWsCopy): DetailSection[] {
  return [
    { title: m.sectionCapability, content: (
      <DetailLoader name={NAME} id={`${no}:capability`} load={() => api.merchants.capability(no)}>
        {(d) => <MiniTable<CapabilityItem> rows={d.items} rowKey={(r) => r.capability} empty={m.emptyCapability} columns={[
          { header: m.capability, cell: (r) => (m as Record<string, string>)[`cap_${r.capability}`] ?? r.capability },
          { header: m.stage, cell: (r) => r.stage },
          { header: m.available, cell: (r) => yesNo(r.available, c) },
          { header: m.application, cell: (r) => r.applicationNo ? `${r.applicationNo} · ${r.status ?? ""}` : "—" },
          { header: m.note, cell: (r) => r.note ?? "—" },
        ]} />}
      </DetailLoader>
    ) },
    { title: m.sectionChannels, content: (
      <DetailLoader name={NAME} id={`${no}:channels`} load={() => api.merchants.channels(no)}>
        {(d) => <MiniTable<MerchantChannelRow> rows={d.rows} rowKey={(r) => `${r.payChannel}:${r.payMerchantNo ?? r.onboardingNo}`} empty={m.emptyChannels} columns={[
          { header: c.colChannel, cell: (r) => `${r.channelName ?? r.payChannel}${r.test ? ` · ${m.testFlag}` : ""}` },
          { header: m.channelStage, cell: (r) => (m as Record<string, string>)[`stage_${r.stage}`] ?? r.stage },
          { header: m.payMerchantNo, cell: (r) => r.payMerchantNo ?? r.onboardingNo ?? "—" },
          { header: m.boundStores, cell: (r) => r.boundStoreCount },
          { header: m.products, cell: (r) => r.products.map((p) => p.productName ?? p.productCode).join("、") || "—" },
        ]} />}
      </DetailLoader>
    ) },
    { title: m.sectionPolicy, content: (
      <DetailLoader name={NAME} id={`${no}:policy`} load={() => api.merchants.policy(no)}>
        {(p) => <p className="txt-body">{(m as Record<string, string>)[`mode_${p.mode}`] ?? p.mode}{p.mode === "CUSTOM" ? `：${joinCsv(p.productCodes)}` : ""}</p>}
      </DetailLoader>
    ) },
    { title: m.sectionEvents, content: (
      <DetailLoader name={NAME} id={`${no}:events`} load={() => api.merchants.sourceEvents(no, { page: 1, size: 20 })}>
        {(d) => <MiniTable<SourceEvent> rows={d.records} rowKey={(r) => String(r.eventNo ?? `${r.eventType}:${r.occurredAt}`)} empty={m.emptyEvents} columns={[
          { header: m.eventType, cell: (r) => r.eventType },
          { header: m.summary, cell: (r) => r.summary ?? "—" },
          { header: m.occurredAt, cell: (r) => fmtTime(r.occurredAt ?? r.createdAt ?? null) },
        ]} />}
      </DetailLoader>
    ) },
  ];
}

type Run = <R>(fn: () => Promise<R>, ok?: string) => Promise<R>;
type DraftRef = { operator: string; page: string; action: string; id?: string } | null;

function AssistForm({ api, row, run, draft, onDone }: { api: PayCenterApi; row: MerchantRow; run: Run; draft: DraftRef; onDone: () => void }) {
  const m = useMws();
  const label = (k: AssistedField) => (m as Record<string, string>)[`f_${k}`];
  return (
    <DetailLoader name={NAME} id={`${row.merchantNo}:assist`} load={() => api.merchants.assistedDraft(row.merchantNo)}>
      {(d) => (
        <>
          <Notice tone="info" className="mb-4">{m.assistNote}</Notice>
          {d.state === "SUBMITTED" && <Notice tone="warning" className="mb-4">{fill(m.assistSubmittedNote, { rev: d.revisionNo ?? "—" })}</Notice>}
          <ActionForm
            notify={false}
            fields={ASSISTED_FIELDS.map((k) => ({
              key: k, label: label(k), maxLength: MAX[k], sensitive: SENSITIVE.includes(k),
              help: d.filledFields.includes(k) ? (SENSITIVE.includes(k) ? m.filledHidden : fill(m.filled, { v: d.profile[k] ?? "" })) : undefined,
            }))}
            initial={Object.fromEntries(ASSISTED_FIELDS.map((k) => [k, ""])) as Record<AssistedField, string>}
            draft={draft}
            baseVersion={d.version}
            onSubmit={(v) => {
              const fields = Object.fromEntries(Object.entries(v).map(([k, x]) => [k, String(x ?? "").trim()]).filter(([, x]) => x !== ""));
              if (!Object.keys(fields).length) { notify.error(m.assistNothing); return Promise.reject(new Error(m.assistNothing)); }
              return run(() => api.merchants.saveAssisted(row.merchantNo, { expectedVersion: d.version, fields }));
            }}
            onDone={onDone}
          />
        </>
      )}
    </DetailLoader>
  );
}

function PolicyForm({ api, row, run, draft, onDone }: { api: PayCenterApi; row: MerchantRow; run: Run; draft: DraftRef; onDone: () => void }) {
  const m = useMws();
  const c = usePc();
  const products = useQuery({ queryKey: ["pay-center", NAME, "policy-products", api.source], queryFn: () => api.products.list({ page: 1, size: 100 }), staleTime: 60_000 });
  return (
    <DetailLoader name={NAME} id={`${row.merchantNo}:policy`} load={() => api.merchants.policy(row.merchantNo)}>
      {(p) => (
        <>
          <Notice tone="info" className="mb-4">{m.editPolicyNote}</Notice>
          <ActionForm
            notify={false}
            fields={[
              { key: "mode", label: m.policyMode, type: "select", required: true, options: [{ value: "UNRESTRICTED", label: m.mode_UNRESTRICTED }, { value: "CUSTOM", label: m.mode_CUSTOM }] },
              { key: "productCodes", label: m.policyProducts, type: "multiselect", csv: true, options: (products.data?.records ?? []).map((x) => ({ value: x.productCode, label: `${x.displayName} · ${x.productCode}` })) },
              reasonField(c),
            ]}
            initial={{ mode: p.mode === "CUSTOM" ? "CUSTOM" : "UNRESTRICTED", productCodes: p.productCodes.join(","), reason: "" }}
            draft={draft}
            baseVersion={p.version}
            onSubmit={(v) => {
              const codes = String(v.productCodes ?? "").split(",").filter(Boolean);
              if (v.mode === "CUSTOM" && !codes.length) { notify.error(m.policyNeedProducts); return Promise.reject(new Error(m.policyNeedProducts)); }
              return run(() => api.merchants.savePolicy(row.merchantNo, { expectedVersion: p.version, mode: String(v.mode), productCodes: v.mode === "CUSTOM" ? codes : [], reason: String(v.reason) }));
            }}
            onDone={onDone}
          />
        </>
      )}
    </DetailLoader>
  );
}

function VerifyLinkForm({ api, row, run, draft, onDone }: { api: PayCenterApi; row: MerchantRow; run: Run; draft: DraftRef; onDone: () => void }) {
  const m = useMws();
  const c = usePc();
  return (
    <DetailLoader name={NAME} id={row.merchantNo} load={() => api.merchants.detail(row.merchantNo)}>
      {(d) => {
        const links = (d.record as MerchantDetail).sourceLinks;
        if (!links.length) return <Notice tone="info">{m.noLinks}</Notice>;
        return (
          <ActionForm
            notify={false}
            fields={[
              { key: "linkNo", label: m.sourceLink, type: "select", required: true, options: links.map((l) => ({ value: l.linkNo, label: `${l.externalEntityId} · ${l.entityName ?? ""} · ${l.verifyStatus}` })) },
              { key: "result", label: m.verifyResult, type: "select", required: true, options: [{ value: "VERIFIED", label: m.result_VERIFIED }, { value: "MISMATCH", label: m.result_MISMATCH }] },
              reasonField(c),
            ]}
            initial={{ linkNo: links.find((l) => l.verifyStatus === "PENDING")?.linkNo ?? links[0].linkNo, result: "VERIFIED", reason: "" }}
            draft={draft}
            onSubmit={(v) => {
              const link = links.find((l) => l.linkNo === v.linkNo)!;
              return run(() => api.merchants.verifyLink(row.merchantNo, link.linkNo, { expectedVersion: link.version, result: v.result as "VERIFIED", reason: String(v.reason) }));
            }}
            onDone={onDone}
          />
        );
      }}
    </DetailLoader>
  );
}

/** 选资料版本（默认最新一版）。 */
function useRevisionPicker(api: PayCenterApi, row: MerchantRow) {
  const q = useQuery({ queryKey: ["pay-center", NAME, "detail", api.source, row.merchantNo], queryFn: () => api.merchants.detail(row.merchantNo) });
  const revisions = (q.data?.record as MerchantDetail | undefined)?.profiles ?? [];
  const [picked, setPicked] = React.useState<string>("");
  const current = revisions.find((r) => r.revisionNo === picked) ?? revisions.find((r) => r.reviewStatus === "SUBMITTED") ?? revisions[0];
  return { q, revisions, current, setPicked };
}

function RevisionSelect({ revisions, value, onChange }: { revisions: MerchantDetail["profiles"]; value: string; onChange: (v: string) => void }) {
  const m = useMws();
  return (
    <label className="mb-4 flex items-center gap-2 txt-body">
      <span className="text-muted-foreground">{m.revisionPick}</span>
      <select className="h-[var(--ctl-h)] focus-ring rounded-control bg-muted px-2" value={value} onChange={(e) => onChange(e.target.value)}>
        {revisions.map((r) => <option key={r.revisionNo} value={r.revisionNo}>{`r${r.revision} · ${r.purpose} · ${r.reviewStatus}`}</option>)}
      </select>
    </label>
  );
}

/** 主体资料核对：字段、证件预览（object URL，离开即回收）、证照识别比对（只看不存）。 */
function EntityPanel({ api, row }: { api: PayCenterApi; row: MerchantRow }) {
  const m = useMws();
  const c = usePc();
  const pick = useRevisionPicker(api, row);
  const rev = pick.current;
  const materials = useQuery({
    queryKey: ["pay-center", NAME, "entity", api.source, row.merchantNo, rev?.revisionNo], enabled: !!rev,
    queryFn: () => api.merchants.entityMaterials(row.merchantNo, rev!.revisionNo), gcTime: 0, staleTime: 0, retry: false,
  });
  if (pick.q.isPending) return <Skeleton className="h-24 w-full" />;
  if (!rev) return <Notice tone="info">{c.emptyProfiles}</Notice>;
  return (
    <>
      <Notice tone="info" className="mb-4">{m.entityNote}</Notice>
      <RevisionSelect revisions={pick.revisions} value={rev.revisionNo} onChange={pick.setPicked} />
      {materials.isPending ? <Skeleton className="h-24 w-full" /> : materials.error ? <Notice tone="info">{m.noEntity}</Notice> : (
        <EntityMaterialsView api={api} merchantNo={row.merchantNo} revisionNo={rev.revisionNo} data={materials.data!} />
      )}
    </>
  );
}

function EntityMaterialsView({ api, merchantNo, revisionNo, data }: { api: PayCenterApi; merchantNo: string; revisionNo: string; data: EntityMaterials }) {
  const m = useMws();
  const [viewer, setViewer] = React.useState<{ purpose: string; url: string; kind?: string } | null>(null);
  const [reading, setReading] = React.useState<{ purpose: string; busy: boolean; rows?: ComparisonRow[]; result?: RecognizeResult; error?: string } | null>(null);
  const urlRef = React.useRef<string | null>(null);
  const release = () => { if (urlRef.current) URL.revokeObjectURL(urlRef.current); urlRef.current = null; };
  React.useEffect(() => release, []);
  const fileNoOf = (ref: string) => ref.replace(/^file:entity-profile\//, "");
  const fetchDoc = async (purpose: string) => {
    const blob = await api.merchants.entityFile(merchantNo, revisionNo, fileNoOf(data.documents[purpose]));
    const kind = sniffEvidence(new Uint8Array(await blob.slice(0, 8).arrayBuffer()));
    return { blob: kind ? new Blob([blob], { type: kind }) : blob, kind };
  };
  const view = async (purpose: string) => {
    try {
      const { blob, kind } = await fetchDoc(purpose);
      release(); urlRef.current = URL.createObjectURL(blob);
      setViewer({ purpose, url: urlRef.current, kind });
    } catch (e) { notify.error(e instanceof Error ? e.message : String(e)); }
  };
  const recognize = async (purpose: string) => {
    const type = recognitionTypeOf(purpose);
    if (!type) return;
    setReading({ purpose, busy: true });
    try {
      const { blob, kind } = await fetchDoc(purpose);
      const ext = kind === "application/pdf" ? "pdf" : kind === "image/png" ? "png" : "jpg";
      const result = await api.documents.recognize(new File([blob], `${purpose.toLowerCase()}.${ext}`, { type: kind ?? "application/octet-stream" }), type);
      setReading({ purpose, busy: false, result, rows: compareRecognition(result, data.fields) });
    } catch (e) { setReading({ purpose, busy: false, error: e instanceof Error ? e.message : String(e) }); }
  };
  const fields = Object.entries(data.fields);
  const docLabel = (p: string) => (m as Record<string, string>)[`doc_${p}`] ?? p;
  return (
    <div className="space-y-4">
      <section>
        <h4 className="mb-2 txt-label text-muted-foreground">{m.entityFields}</h4>
        <MiniTable<[string, string | null]> rows={fields} rowKey={(r) => r[0]} empty={m.noEntity} columns={[
          { header: m.cmpField, cell: (r) => r[0] },
          { header: m.cmpSubmitted, cell: (r) => r[1] ?? "—" },
        ]} />
      </section>
      <section>
        <h4 className="mb-2 txt-label text-muted-foreground">{m.entityDocs}</h4>
        <MiniTable<string> rows={Object.keys(data.documents)} rowKey={(p) => p} empty={m.noEntity} columns={[
          { header: m.docPurpose, cell: (p) => docLabel(p) },
          { header: "", cell: (p) => (
            <span className="flex gap-2">
              <Button size="sm" variant="outline" onClick={() => void view(p)}>{m.viewDoc}</Button>
              {recognitionTypeOf(p) && <Button size="sm" variant="outline" disabled={reading?.busy} onClick={() => void recognize(p)}>{m.recognizeDoc}</Button>}
            </span>
          ) },
        ]} />
      </section>
      {viewer && (
        <section>
          <div className="mb-2 flex items-center justify-between"><h4 className="txt-label text-muted-foreground">{docLabel(viewer.purpose)}</h4><Button size="sm" variant="ghost" onClick={() => { release(); setViewer(null); }}>{m.closePreview}</Button></div>
          {viewer.kind?.startsWith("image/")
            ? <img src={viewer.url} alt={docLabel(viewer.purpose)} className="max-h-96 rounded-card" />
            : <a href={viewer.url} download={`${viewer.purpose.toLowerCase()}.pdf`} className="text-primary-ink hover:underline focus-ring txt-body">{m.pdfDownload}</a>}
        </section>
      )}
      {reading && !reading.busy && (
        <section>
          <h4 className="mb-2 txt-label text-muted-foreground">{docLabel(reading.purpose)} · {m.recognizeDoc}</h4>
          {reading.error ? <Notice tone="danger">{reading.error}</Notice> : !reading.result?.recognized ? <Notice tone="info">{m.notRecognized}</Notice> : (
            <MiniTable<ComparisonRow> rows={reading.rows ?? []} rowKey={(r) => r.key} empty={m.notRecognized} columns={[
              { header: m.cmpField, cell: (r) => r.key },
              { header: m.cmpSubmitted, cell: (r) => r.submitted ?? "—" },
              { header: m.cmpRecognized, cell: (r) => r.recognized },
              { header: m.cmpResult, cell: (r) => <StatusBadge value={r.state} map={{ MATCH: { label: m.cmp_MATCH, tone: "success" }, MISMATCH: { label: m.cmp_MISMATCH, tone: "danger" }, NOT_SUBMITTED: { label: m.cmp_NOT_SUBMITTED, tone: "muted" } }} /> },
            ]} />
          )}
        </section>
      )}
    </div>
  );
}

/** 退回指定资料补件：勾字段 / 证件 + 给商户看的理由。 */
function EntityMoreForm({ api, row, run, onDone }: { api: PayCenterApi; row: MerchantRow; run: Run; onDone: () => void }) {
  const m = useMws();
  const c = usePc();
  const pick = useRevisionPicker(api, row);
  const rev = pick.current;
  const materials = useQuery({ queryKey: ["pay-center", NAME, "entity", api.source, row.merchantNo, rev?.revisionNo], enabled: !!rev, queryFn: () => api.merchants.entityMaterials(row.merchantNo, rev!.revisionNo), gcTime: 0, retry: false });
  if (pick.q.isPending || (rev && materials.isPending)) return <Skeleton className="h-24 w-full" />;
  if (!rev) return <Notice tone="info">{c.emptyProfiles}</Notice>;
  if (materials.error || !materials.data) return (<><RevisionSelect revisions={pick.revisions} value={rev.revisionNo} onChange={pick.setPicked} /><Notice tone="info">{m.noEntity}</Notice></>);
  const data = materials.data;
  return (
    <>
      <RevisionSelect revisions={pick.revisions} value={rev.revisionNo} onChange={pick.setPicked} />
      <ActionForm
        notify={false}
        fields={[
          { key: "fields", label: m.moreFields, type: "multiselect", csv: true, options: Object.keys(data.fields).map((k) => ({ value: k, label: k })) },
          { key: "documents", label: m.moreDocs, type: "multiselect", csv: true, options: Object.keys(data.documents).map((k) => ({ value: k, label: (m as Record<string, string>)[`doc_${k}`] ?? k })) },
          reasonField(c),
        ]}
        initial={{ fields: "", documents: "", reason: "" }}
        draft={null}
        onSubmit={(v) => {
          const fields = String(v.fields ?? "").split(",").filter(Boolean);
          const documents = String(v.documents ?? "").split(",").filter(Boolean);
          if (!fields.length && !documents.length) { notify.error(m.moreNeedOne); return Promise.reject(new Error(m.moreNeedOne)); }
          return run(() => api.merchants.entityRequestMore(row.merchantNo, rev.revisionNo, { expectedVersion: rev.version, reason: String(v.reason), fields, documents }), m.moreDone);
        }}
        onDone={onDone}
      />
    </>
  );
}

