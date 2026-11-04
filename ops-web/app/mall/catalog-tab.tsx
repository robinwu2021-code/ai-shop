"use client";

// 经营类型 · 业务端 · 三档 Plan · 行业发布。
//
// 编辑权 (commerce:catalog:edit)与发布权 (commerce:catalog:publish)分开：
// 起草谁都能起草，冻结给门店买只能是有发布权的人。已发布的 Plan 版本只读 ——
// 要改就起一个新草稿再发布，已落单的订单仍按它下单时的快照。
import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import type {
  MallBusinessEndView, MallBusinessTypeView, MallEndScope, MallPlanTier, MallPlanVersionView, MallReleaseStatus,
} from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CheckboxField } from "@/components/ui/checkbox";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { HelpNote } from "@/components/ui/help-note";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";
import { StatusBadge } from "@/components/ui/status-badge";
import { Textarea } from "@/components/ui/textarea";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { newOpNo, saleMap, showErr, versionMap, type MallCopy } from "./shared";

const TIERS: MallPlanTier[] = ["FREE", "PRO", "CHAIN"];
const CATALOG_KEYS = [["mall-catalog"], ["mall-types"], ["mall-ends"], ["mall-plan-defs"]] as const;

function useInvalidateCatalog() {
  const qc = useQueryClient();
  return () => CATALOG_KEYS.forEach((k) => qc.invalidateQueries({ queryKey: [...k] }));
}

export function CatalogTab({ c, canEdit, canPublish }: { c: MallCopy; canEdit: boolean; canPublish: boolean }) {
  return (
    <div className="space-y-8">
      {!canEdit && <ReadOnlyNotice what={c.catalogEditWhat} perm="commerce:catalog:edit" note={c.catalogEditNote} />}
      <HelpNote>{c.catalogHelp}</HelpNote>
      <TypesSection c={c} canEdit={canEdit} canPublish={canPublish} />
      <EndsSection c={c} canEdit={canEdit} />
      <PlansSection c={c} canEdit={canEdit} canPublish={canPublish} />
    </div>
  );
}

// ── 经营类型 ──────────────────────────────────────────────────────────

interface TypeForm { creating: boolean; typeCode: string; name: string; sortOrder: string; enabled: boolean; expectedVersion: number; reason: string }

function TypesSection({ c, canEdit, canPublish }: { c: MallCopy; canEdit: boolean; canPublish: boolean }) {
  const types = useQuery({ queryKey: ["mall-types"], queryFn: () => api.mallBusinessTypes() });
  const defs = useQuery({ queryKey: ["mall-plan-defs"], queryFn: () => api.mallPlanDefinitions() });
  const invalidate = useInvalidateCatalog();
  const { confirm, dialog } = useConfirm();
  const [form, setForm] = React.useState<TypeForm | null>(null);

  const published = (code?: string) => (defs.data ?? []).some((d) => d.typeCode === code && d.currentVersionNo);

  const save = useMutation({
    mutationFn: (f: TypeForm) => f.creating
      ? api.createMallBusinessType({ typeCode: f.typeCode.trim(), name: f.name.trim(), sortOrder: Number(f.sortOrder) || 0,
          enabled: false, expectedVersion: 0, reason: f.reason.trim() })
      : api.updateMallBusinessType(f.typeCode, { name: f.name.trim(), sortOrder: Number(f.sortOrder) || 0, enabled: f.enabled,
          expectedVersion: f.expectedVersion, reason: f.reason.trim() }),
    onSuccess: () => { invalidate(); setForm(null); notify.success(c.toastSaved); },
  });

  const toggle = (row: MallBusinessTypeView) => confirm({
    title: row.draft?.enabled ? c.typeDisableTitle : c.typeEnableTitle,
    desc: row.draft?.enabled ? c.typeDisableDesc : c.typeEnableDesc,
    requireReason: true,
    action: (reason) => api.updateMallBusinessType(row.draft!.typeCode!, {
      name: row.draft!.name!, sortOrder: row.draft!.sortOrder ?? 0, enabled: !row.draft!.enabled, expectedVersion: row.version!, reason,
    }).then(() => { invalidate(); notify.success(c.toastSaved); }),
  });

  const columns: Column<MallBusinessTypeView>[] = [
    { header: c.colTypeCode, cell: (r) => <code className="txt-caption">{r.draft?.typeCode}</code> },
    { header: c.colName, cell: (r) => r.draft?.name },
    { header: c.colSort, numeric: true, cell: (r) => r.draft?.sortOrder },
    { header: c.colCatalog, cell: (r) => published(r.draft?.typeCode) ? <Badge tone="success">{c.typePublished}</Badge> : <Badge tone="muted">{c.typeUnpublished}</Badge> },
    { header: c.colEnabled, cell: (r) => r.draft?.enabled ? <Badge tone="success">{c.enabledOn}</Badge> : <Badge tone="muted">{c.enabledOff}</Badge> },
    { header: c.colVersion, numeric: true, cell: (r) => r.version },
    {
      header: c.colActions, cell: (r) => (
        <span className="flex gap-2">
          {canEdit && (
            <Button size="sm" variant="outline" onClick={() => setForm({ creating: false, typeCode: r.draft!.typeCode!, name: r.draft!.name ?? "",
              sortOrder: String(r.draft?.sortOrder ?? 0), enabled: !!r.draft?.enabled, expectedVersion: r.version!, reason: "" })}>{c.btnEdit}</Button>
          )}
          {canPublish && (
            <Button size="sm" variant="outline" disabled={!r.draft?.enabled && !published(r.draft?.typeCode)} onClick={() => toggle(r)}>
              {r.draft?.enabled ? c.btnDisable : c.btnEnable}
            </Button>
          )}
        </span>
      ),
    },
  ];

  const valid = form && form.typeCode.trim() && form.name.trim() && form.reason.trim();
  return (
    <section>
      <SectionHeader title={c.secTypes} desc={c.secTypesDesc}
        summary={canEdit && <Button size="sm" onClick={() => setForm({ creating: true, typeCode: "", name: "", sortOrder: "0", enabled: false, expectedVersion: 0, reason: "" })}>{c.btnNewType}</Button>} />
      <DataTable columns={columns} rows={types.data} loading={types.isLoading} error={showErr(types.error)} onRetry={() => types.refetch()}
        rowKey={(r) => r.draft?.typeCode ?? ""} empty={c.typesEmpty} />
      <Drawer open={!!form} onOpenChange={(o) => !o && setForm(null)} title={form?.creating ? c.btnNewType : c.typeEditTitle}
        footer={<Button disabled={!valid} loading={save.isPending} onClick={() => form && save.mutate(form)}>{c.btnSave}</Button>}>
        {form && (
          <div className="space-y-3">
            {form.creating && <Notice tone="info">{c.typeCreateNotice}</Notice>}
            <div className="space-y-1">
              <Label htmlFor="ty-code" required>{c.colTypeCode}</Label>
              <Input id="ty-code" value={form.typeCode} disabled={!form.creating} placeholder="RETAIL"
                onChange={(e) => setForm((f) => f && { ...f, typeCode: e.target.value.toUpperCase() })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="ty-name" required>{c.colName}</Label>
              <Input id="ty-name" value={form.name} onChange={(e) => setForm((f) => f && { ...f, name: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="ty-sort">{c.colSort}</Label>
              <Input id="ty-sort" inputMode="numeric" value={form.sortOrder} onChange={(e) => setForm((f) => f && { ...f, sortOrder: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="ty-reason" required>{c.fieldReason}</Label>
              <Textarea id="ty-reason" value={form.reason} placeholder={c.reasonPh} onChange={(v) => setForm((f) => f && { ...f, reason: v })} />
            </div>
          </div>
        )}
      </Drawer>
      {dialog}
    </section>
  );
}

// ── 业务端 ────────────────────────────────────────────────────────────

interface EndForm {
  creating: boolean; deploymentNo: string; code: string; entitlementCode: string; name: string; scope: MallEndScope;
  allowedBusinessTypes: string[]; releaseStatus: MallReleaseStatus; saleEnabled: boolean; bootstrapRequired: boolean;
  enabled: boolean; expectedVersion: number; reason: string;
}

function EndsSection({ c, canEdit }: { c: MallCopy; canEdit: boolean }) {
  const ends = useQuery({ queryKey: ["mall-ends"], queryFn: () => api.mallBusinessEnds() });
  const types = useQuery({ queryKey: ["mall-types"], queryFn: () => api.mallBusinessTypes() });
  const deployments = useQuery({ queryKey: ["mall-deployments"], queryFn: () => api.mallDeploymentOptions() });
  const invalidate = useInvalidateCatalog();
  const [form, setForm] = React.useState<EndForm | null>(null);

  const save = useMutation({
    mutationFn: (f: EndForm) => api.saveMallBusinessEnd({
      code: f.code, entitlementCode: f.entitlementCode, name: f.name.trim(), scope: f.scope, allowedBusinessTypes: f.allowedBusinessTypes,
      releaseStatus: f.releaseStatus, saleEnabled: f.saleEnabled, bootstrapRequired: f.bootstrapRequired, enabled: f.enabled,
      deploymentNo: f.deploymentNo || null, expectedVersion: f.expectedVersion, reason: f.reason.trim(),
    }),
    onSuccess: () => { invalidate(); setForm(null); notify.success(c.toastSaved); },
  });

  const pick = (no: string) => {
    const d = deployments.data?.find((x) => x.deploymentNo === no);
    setForm((f) => f && (d
      ? { ...f, deploymentNo: no, code: d.code ?? "", entitlementCode: d.entitlementCode ?? "", name: f.name || (d.name ?? ""),
          allowedBusinessTypes: f.allowedBusinessTypes.filter((t) => d.allowedBusinessTypes?.includes(t)) }
      : { ...f, deploymentNo: "", releaseStatus: f.releaseStatus === "VERIFIED" ? "PLANNED" : f.releaseStatus }));
  };

  const edit = (r: MallBusinessEndView) => {
    const d = r.draft!;
    const dep = deployments.data?.find((x) => x.acceptanceRef && x.acceptanceRef === d.acceptanceRef);
    setForm({ creating: false, deploymentNo: dep?.deploymentNo ?? "", code: d.code!, entitlementCode: d.entitlementCode!, name: d.name ?? "",
      scope: d.scope ?? "STORE", allowedBusinessTypes: d.allowedBusinessTypes ?? [], releaseStatus: d.releaseStatus ?? "PLANNED",
      saleEnabled: !!d.saleEnabled, bootstrapRequired: !!d.bootstrapRequired, enabled: !!d.enabled, expectedVersion: r.version!, reason: "" });
  };

  const selectedDep = deployments.data?.find((x) => x.deploymentNo === form?.deploymentNo);
  const columns: Column<MallBusinessEndView>[] = [
    { header: c.colEndCode, cell: (r) => <code className="txt-caption">{r.draft?.code}</code> },
    { header: c.colName, cell: (r) => r.draft?.name },
    { header: c.colTypes, cell: (r) => (r.draft?.allowedBusinessTypes ?? []).join(" / ") },
    { header: c.colRelease, cell: (r) => <Badge tone={r.draft?.releaseStatus === "VERIFIED" ? "success" : r.draft?.releaseStatus === "RETIRED" ? "muted" : "warning"}>{r.draft?.releaseStatus}</Badge> },
    { header: c.colEndFlags, cell: (r) => (
      <span className="flex flex-wrap gap-1">
        {r.draft?.bootstrapRequired && <Badge tone="info">{c.endBootstrap}</Badge>}
        {r.draft?.saleEnabled && <Badge tone="default">{c.endSaleEnabled}</Badge>}
        {!r.draft?.enabled && <Badge tone="muted">{c.enabledOff}</Badge>}
      </span>
    ) },
    { header: c.colAcceptance, cell: (r) => r.draft?.acceptanceRef ? <code className="txt-caption">{r.draft.acceptanceRef}</code> : <span className="text-muted-foreground">{c.endNoAcceptance}</span> },
    { header: c.colVersion, numeric: true, cell: (r) => r.version },
    { header: c.colActions, cell: (r) => canEdit ? <Button size="sm" variant="outline" onClick={() => edit(r)}>{c.btnEdit}</Button> : null },
  ];

  const valid = form && form.code && form.entitlementCode && form.name.trim() && form.reason.trim() && form.allowedBusinessTypes.length > 0
    && (form.releaseStatus !== "VERIFIED" || !!form.deploymentNo);
  return (
    <section>
      <SectionHeader title={c.secEnds} desc={c.secEndsDesc}
        summary={canEdit && <Button size="sm" onClick={() => setForm({ creating: true, deploymentNo: "", code: "", entitlementCode: "", name: "", scope: "STORE",
          allowedBusinessTypes: [], releaseStatus: "PLANNED", saleEnabled: false, bootstrapRequired: false, enabled: false, expectedVersion: 0, reason: "" })}>{c.btnNewEnd}</Button>} />
      <DataTable columns={columns} rows={ends.data} loading={ends.isLoading} error={showErr(ends.error)} onRetry={() => ends.refetch()}
        rowKey={(r) => r.draft?.code ?? ""} empty={c.endsEmpty} />
      <Drawer open={!!form} onOpenChange={(o) => !o && setForm(null)} title={form?.creating ? c.btnNewEnd : c.endEditTitle} width="w-[520px]"
        footer={<Button disabled={!valid} loading={save.isPending} onClick={() => form && save.mutate(form)}>{c.btnSave}</Button>}>
        {form && (
          <div className="space-y-3">
            <Notice tone="info">{c.endDeploymentNotice}</Notice>
            <div className="space-y-1">
              <Label htmlFor="end-dep">{c.fieldDeployment}</Label>
              <Select id="end-dep" className="w-full" value={form.deploymentNo} onChange={(e) => pick(e.target.value)}>
                <option value="">{c.deploymentNone}</option>
                {(deployments.data ?? []).map((d) => <option key={d.deploymentNo} value={d.deploymentNo}>{d.name} ({d.code})</option>)}
              </Select>
              {deployments.data?.length === 0 && <p className="txt-caption text-muted-foreground">{c.deploymentEmpty}</p>}
            </div>
            {selectedDep && (
              <FieldGrid>
                <Field label={c.fieldClientId}><code className="txt-caption">{selectedDep.clientId}</code></Field>
                <Field label={c.fieldRoutePolicy}><code className="txt-caption">{selectedDep.routePolicyRef}</code></Field>
                <Field label={c.fieldAcceptance}><code className="txt-caption">{selectedDep.acceptanceRef}</code></Field>
              </FieldGrid>
            )}
            <div className="space-y-1">
              <Label htmlFor="end-code" required>{c.colEndCode}</Label>
              <Input id="end-code" value={form.code} disabled={!!form.deploymentNo || !form.creating}
                onChange={(e) => setForm((f) => f && { ...f, code: e.target.value.toUpperCase() })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="end-ent" required>{c.fieldEntitlement}</Label>
              <Input id="end-ent" value={form.entitlementCode} disabled={!!form.deploymentNo || !form.creating}
                onChange={(e) => setForm((f) => f && { ...f, entitlementCode: e.target.value.toUpperCase() })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="end-name" required>{c.colName}</Label>
              <Input id="end-name" value={form.name} onChange={(e) => setForm((f) => f && { ...f, name: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label required>{c.colTypes}</Label>
              <div className="flex flex-wrap gap-3">
                {(types.data ?? []).map((t) => {
                  const code = t.draft!.typeCode!;
                  const off = !!selectedDep && !selectedDep.allowedBusinessTypes?.includes(code);
                  return (
                    <CheckboxField key={code} label={`${t.draft?.name} (${code})`} disabled={off} checked={form.allowedBusinessTypes.includes(code)}
                      onChange={(v) => setForm((f) => f && { ...f, allowedBusinessTypes: v === true ? [...f.allowedBusinessTypes, code] : f.allowedBusinessTypes.filter((x) => x !== code) })} />
                  );
                })}
              </div>
            </div>
            <div className="space-y-1">
              <Label htmlFor="end-release">{c.colRelease}</Label>
              <Select id="end-release" className="w-full" value={form.releaseStatus}
                onChange={(e) => setForm((f) => f && { ...f, releaseStatus: e.target.value as MallReleaseStatus })}>
                <option value="PLANNED">{c.releasePlanned}</option>
                <option value="VERIFIED" disabled={!form.deploymentNo}>{c.releaseVerified}</option>
                <option value="RETIRED">{c.releaseRetired}</option>
              </Select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="end-scope">{c.fieldScope}</Label>
              <Select id="end-scope" className="w-full" value={form.scope} onChange={(e) => setForm((f) => f && { ...f, scope: e.target.value as MallEndScope })}>
                <option value="STORE">{c.scopeStore}</option>
                <option value="SYSTEM">{c.scopeSystem}</option>
              </Select>
            </div>
            <div className="flex flex-wrap gap-4">
              <CheckboxField label={c.endBootstrap} checked={form.bootstrapRequired} onChange={(v) => setForm((f) => f && { ...f, bootstrapRequired: v === true })} />
              <CheckboxField label={c.endSaleEnabled} checked={form.saleEnabled} onChange={(v) => setForm((f) => f && { ...f, saleEnabled: v === true })} />
              <CheckboxField label={c.enabledOn} checked={form.enabled} onChange={(v) => setForm((f) => f && { ...f, enabled: v === true })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="end-reason" required>{c.fieldReason}</Label>
              <Textarea id="end-reason" value={form.reason} placeholder={c.reasonPh} onChange={(v) => setForm((f) => f && { ...f, reason: v })} />
            </div>
          </div>
        )}
      </Drawer>
    </section>
  );
}

// ── 三档 Plan 与行业发布 ──────────────────────────────────────────────

interface PlanForm {
  planNo: string; tier: MallPlanTier; typeCode: string; planVersionNo: string | null; displayName: string; summary: string;
  entitlementCodes: string[]; policyVersion: string; expectedVersion: number; reason: string;
}

function PlansSection({ c, canEdit, canPublish }: { c: MallCopy; canEdit: boolean; canPublish: boolean }) {
  const catalog = useQuery({ queryKey: ["mall-catalog"], queryFn: () => api.mallCatalog() });
  const defs = useQuery({ queryKey: ["mall-plan-defs"], queryFn: () => api.mallPlanDefinitions() });
  const invalidate = useInvalidateCatalog();
  const { confirm, dialog } = useConfirm();
  const types = catalog.data?.businessTypes ?? [];
  const [type, setType] = React.useState("");
  const typeCode = type || types[0]?.draft?.typeCode || "";
  const [form, setForm] = React.useState<PlanForm | null>(null);

  const typeDefs = (defs.data ?? []).filter((d) => d.typeCode === typeCode);
  const versionsOf = (planNo?: string) => (catalog.data?.planVersions ?? []).filter((v) => v.draft?.planNo === planNo)
    .sort((a, b) => (b.revision ?? 0) - (a.revision ?? 0));
  const ends = catalog.data?.businessEnds ?? [];

  const save = useMutation({
    mutationFn: (f: PlanForm) => api.saveMallPlanVersion(f.planNo, {
      planVersionNo: f.planVersionNo, typeCode: f.typeCode, tier: f.tier, displayName: f.displayName.trim(),
      summary: f.summary.trim() || null, entitlementCodes: f.entitlementCodes, policyVersion: f.policyVersion.trim(),
      expectedVersion: f.expectedVersion, reason: f.reason.trim(),
    }),
    onSuccess: () => { invalidate(); setForm(null); notify.success(c.toastSaved); },
  });

  const openDraft = (planNo: string, tier: MallPlanTier, from?: MallPlanVersionView) => {
    const editing = from && from.status === "DRAFT";
    setForm({ planNo, tier, typeCode, planVersionNo: editing ? from!.draft!.planVersionNo! : null,
      displayName: from?.draft?.displayName ?? "", summary: from?.draft?.summary ?? "", entitlementCodes: from?.draft?.entitlementCodes ?? [],
      policyVersion: from?.draft?.policyVersion ?? "", expectedVersion: editing ? from!.version! : 0, reason: "" });
  };

  const stop = (planNo: string, version: number) => confirm({
    title: c.planStopTitle, desc: c.planStopDesc, danger: true, requireReason: true,
    action: (reason) => api.stopMallPlanSale(planNo, { operationNo: newOpNo("STOP"), expectedVersion: version, reason })
      .then(() => { invalidate(); notify.success(c.toastStopped); }),
  });

  const valid = form && form.displayName.trim() && form.policyVersion.trim() && form.reason.trim() && form.entitlementCodes.length > 0;
  return (
    <section>
      <SectionHeader title={c.secPlans} desc={c.secPlansDesc}
        summary={
          <span className="flex items-center gap-2">
            <label htmlFor="plan-type" className="txt-label text-muted-foreground">{c.colType}</label>
            <Select id="plan-type" value={typeCode} onChange={(e) => setType(e.target.value)}>
              {types.map((t) => <option key={t.draft?.typeCode} value={t.draft?.typeCode}>{t.draft?.name} ({t.draft?.typeCode})</option>)}
            </Select>
          </span>
        } />
      {catalog.error ? (
        <DataTable columns={[]} rows={undefined} error={showErr(catalog.error)} onRetry={() => catalog.refetch()} rowKey={() => ""} empty={c.plansEmpty} />
      ) : (
        <div className="grid gap-3 lg:grid-cols-3">
          {TIERS.map((tier) => {
            const def = typeDefs.find((d) => d.tier === tier);
            const versions = versionsOf(def?.planNo);
            return (
              <div key={tier} className="rounded-card border border-border bg-card p-4">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <span className="txt-strong">{tier}</span>
                  {def && <StatusBadge map={saleMap(c)} value={def.saleStatus ?? ""} />}
                </div>
                {!def ? <p className="txt-caption text-muted-foreground">{c.planNoDef}</p> : (
                  <>
                    <p className="txt-caption text-muted-foreground">
                      {fill(c.planCurrent, { v: def.currentVersionNo ?? c.planNone })}
                    </p>
                    <ul className="my-3 space-y-2">
                      {versions.length === 0 && <li className="txt-caption text-muted-foreground">{c.planNoVersions}</li>}
                      {versions.map((v) => (
                        <li key={v.draft?.planVersionNo} className="rounded-control border border-border p-2">
                          <div className="flex items-center justify-between gap-2">
                            <code className="txt-caption">{v.draft?.planVersionNo}</code>
                            <StatusBadge map={versionMap(c)} value={v.status ?? ""} />
                          </div>
                          <div className="txt-body">{v.draft?.displayName}</div>
                          <div className="txt-caption text-muted-foreground">{(v.draft?.entitlementCodes ?? []).join(" · ")}</div>
                          {canEdit && (
                            <Button size="sm" variant="ghost" className="mt-1" onClick={() => openDraft(def.planNo!, tier, v)}>
                              {v.status === "DRAFT" ? c.btnEditDraft : c.btnCopyToDraft}
                            </Button>
                          )}
                        </li>
                      ))}
                    </ul>
                    <span className="flex flex-wrap gap-2">
                      {canEdit && <Button size="sm" variant="outline" onClick={() => openDraft(def.planNo!, tier)}>{c.btnNewDraft}</Button>}
                      {canPublish && def.saleStatus === "ON_SALE" && (
                        <Button size="sm" variant="outline" onClick={() => stop(def.planNo!, def.version!)}>{c.btnStopSale}</Button>
                      )}
                    </span>
                  </>
                )}
              </div>
            );
          })}
        </div>
      )}

      {typeCode && <PublishCard key={typeCode} c={c} typeCode={typeCode} canPublish={canPublish} />}

      <Drawer open={!!form} onOpenChange={(o) => !o && setForm(null)} width="w-[520px]"
        title={form ? fill(form.planVersionNo ? c.planEditTitle : c.planNewTitle, { plan: form.planNo }) : ""}
        footer={<Button disabled={!valid} loading={save.isPending} onClick={() => form && save.mutate(form)}>{c.btnSaveDraft}</Button>}>
        {form && (
          <div className="space-y-3">
            <Notice tone="info">{c.planDraftNotice}</Notice>
            <div className="space-y-1">
              <Label htmlFor="pl-name" required>{c.fieldDisplayName}</Label>
              <Input id="pl-name" value={form.displayName} onChange={(e) => setForm((f) => f && { ...f, displayName: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="pl-summary">{c.fieldSummary}</Label>
              <Textarea id="pl-summary" value={form.summary} onChange={(v) => setForm((f) => f && { ...f, summary: v })} />
            </div>
            <div className="space-y-1">
              <Label required>{c.fieldEntitlements}</Label>
              <p className="txt-caption text-muted-foreground">{c.planSupersetHint}</p>
              <div className="flex flex-wrap gap-3">
                {ends.filter((e) => e.draft?.allowedBusinessTypes?.includes(form.typeCode)).map((e) => {
                  const code = e.draft!.entitlementCode!;
                  return (
                    <CheckboxField key={code} label={`${e.draft?.name} (${code})${e.draft?.bootstrapRequired ? c.planBootstrapMark : ""}`}
                      checked={form.entitlementCodes.includes(code)}
                      onChange={(v) => setForm((f) => f && { ...f, entitlementCodes: v === true ? [...f.entitlementCodes, code] : f.entitlementCodes.filter((x) => x !== code) })} />
                  );
                })}
              </div>
            </div>
            <div className="space-y-1">
              <Label htmlFor="pl-policy" required>{c.fieldPolicyVersion}</Label>
              <Input id="pl-policy" value={form.policyVersion} onChange={(e) => setForm((f) => f && { ...f, policyVersion: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="pl-reason" required>{c.fieldReason}</Label>
              <Textarea id="pl-reason" value={form.reason} placeholder={c.reasonPh} onChange={(v) => setForm((f) => f && { ...f, reason: v })} />
            </div>
          </div>
        )}
      </Drawer>
      {dialog}
    </section>
  );
}

/**
 * 行业发布：三档版本 + 候选 SKU 一起冻结。
 *
 * ⚠️ 契约缺口：发布要带 `expectedCatalogVersion`，而运营目录接口**不回读**行业目录版本
 *  (只在发布回执里出现一次)。首次发布必然是 0；再次发布时只能由运营填，
 * 本页把这一会话里最近一次发布回执的版本预填进去。已记入交接说明，待后端在 overview 里补字段。
 */
function PublishCard({ c, typeCode, canPublish }: { c: MallCopy; typeCode: string; canPublish: boolean }) {
  const catalog = useQuery({ queryKey: ["mall-catalog"], queryFn: () => api.mallCatalog() });
  const invalidate = useInvalidateCatalog();
  const { confirm, dialog } = useConfirm();
  const [picked, setPicked] = React.useState<Record<MallPlanTier, string>>({ FREE: "", PRO: "", CHAIN: "" });
  const [skus, setSkus] = React.useState<string[]>([]);
  const [lastCatalog, setLastCatalog] = React.useState<number | null>(null);
  const [catalogInput, setCatalogInput] = React.useState("");
  const [opNo] = React.useState(() => newOpNo("PUB"));

  const data = catalog.data;
  const defs = (data?.planDefinitions ?? []).filter((d) => d.typeCode === typeCode);
  const firstPublish = defs.every((d) => !d.currentVersionNo);
  const versions = (tier: MallPlanTier) => (data?.planVersions ?? []).filter((v) => v.draft?.typeCode === typeCode && v.draft?.tier === tier);
  const candidates = (data?.skuVersions ?? []).filter((v) => v.draft?.allowedBusinessTypes?.includes(typeCode)
    && (v.draft?.components ?? []).some((x) => x.kind === "PLAN"));
  const chosen = TIERS.map((t) => picked[t] || defs.find((d) => d.tier === t)?.currentVersionNo || "");
  const expectedCatalog = firstPublish ? 0 : catalogInput.trim() === "" ? null : Number(catalogInput);

  if (!canPublish) return null;

  const publish = () => confirm({
    title: c.publishTitle, desc: fill(c.publishDesc, { type: typeCode }), requireReason: true,
    action: (reason) => {
      const pv = Object.fromEntries(chosen.map((no) => [no, data!.planVersions!.find((v) => v.draft?.planVersionNo === no)!.version!]));
      const sv = Object.fromEntries(skus.map((no) => [no, data!.skuVersions!.find((v) => v.draft?.skuVersionNo === no)!.version!]));
      const sd = Object.fromEntries(skus.map((no) => {
        const skuNo = data!.skuVersions!.find((v) => v.draft?.skuVersionNo === no)!.draft!.skuNo!;
        return [skuNo, data!.skuDefinitions!.find((d) => d.skuNo === skuNo)!.version!];
      }));
      return api.publishMallIndustry(typeCode, {
        operationNo: opNo, freePlanVersionNo: chosen[0]!, proPlanVersionNo: chosen[1]!, chainPlanVersionNo: chosen[2]!,
        candidateSkuVersionNos: skus, expectedCatalogVersion: expectedCatalog!, expectedPlanVersions: pv, expectedSkuVersions: sv,
        expectedPlanDefinitionVersions: Object.fromEntries(defs.map((d) => [d.planNo!, d.version!])), expectedSkuDefinitionVersions: sd, reason,
      }).then((r) => {
        setLastCatalog(r.catalogVersion ?? null);
        setCatalogInput(String(r.catalogVersion ?? ""));
        invalidate();
        notify.success(fill(c.toastPublished, { v: r.catalogVersion ?? "—" }));
      });
    },
  });

  const ready = chosen.every(Boolean) && expectedCatalog !== null && Number.isInteger(expectedCatalog);
  return (
    <div className="mt-4 rounded-card border border-border bg-card p-4">
      <SectionHeader title={c.secPublish} desc={c.secPublishDesc} />
      <div className="grid gap-3 md:grid-cols-3">
        {TIERS.map((tier) => (
          <div key={tier} className="space-y-1">
            <Label htmlFor={`pub-${tier}`} required>{tier}</Label>
            <Select id={`pub-${tier}`} className="w-full" value={picked[tier] || defs.find((d) => d.tier === tier)?.currentVersionNo || ""}
              onChange={(e) => setPicked((p) => ({ ...p, [tier]: e.target.value }))}>
              <option value="">{c.pickVersion}</option>
              {versions(tier).map((v) => (
                <option key={v.draft?.planVersionNo} value={v.draft?.planVersionNo}>
                  {v.draft?.planVersionNo} · {v.status === "PUBLISHED" ? c.verPublished : c.verDraft}
                </option>
              ))}
            </Select>
          </div>
        ))}
      </div>
      <div className="mt-3 space-y-1">
        <Label>{c.fieldCandidateSkus}</Label>
        {candidates.length === 0 ? <p className="txt-caption text-muted-foreground">{c.noCandidateSkus}</p> : (
          <div className="flex flex-wrap gap-3">
            {candidates.map((v) => {
              const no = v.draft!.skuVersionNo!;
              return (
                <CheckboxField key={no} label={`${v.draft?.title} (${no} · ${v.status === "PUBLISHED" ? c.verPublished : c.verDraft})`}
                  checked={skus.includes(no)} onChange={(x) => setSkus((s) => x === true ? [...s, no] : s.filter((y) => y !== no))} />
              );
            })}
          </div>
        )}
      </div>
      <div className="mt-3 space-y-1">
        <Label htmlFor="pub-catalog" required>{c.fieldCatalogVersion}</Label>
        {firstPublish ? <p className="txt-body">{c.catalogFirst}</p> : (
          <>
            <Input id="pub-catalog" className="w-40" inputMode="numeric" value={catalogInput} placeholder={lastCatalog == null ? "" : String(lastCatalog)}
              onChange={(e) => setCatalogInput(e.target.value.replace(/\D/g, ""))} />
            <Notice tone="warning" className="mt-2">{c.catalogGapNotice}</Notice>
          </>
        )}
      </div>
      <div className="mt-4 flex justify-end">
        <Button disabled={!ready} onClick={publish}>{c.btnPublishIndustry}</Button>
      </div>
      {dialog}
    </div>
  );
}
