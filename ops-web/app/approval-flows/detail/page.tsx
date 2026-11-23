"use client";

// 编辑一条审批流程（TDD-OpsKit审批对接 §1.5，契约 P11–P13、§3.2 ③）：审批步骤与执行节点按顺序排列，可增删、上下移；
// 规则：允许本人审批、同一人不能审两步。保存 = 发新版本（在途单按旧版本走完，返回 runningOnOlderVersions）。
// 校验以 OpsKit 为准：422 的 errors[].path（如 steps[1].approver.value）逐条列出并标到对应步骤上。
// 执行节点的动作代号由业务登记（如 shop.store_opening.apply），这里只读；新加执行节点要填已注册的动作代号。
import * as React from "react";
import { Suspense } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { SwitchField } from "@/components/ui/switch";
import { DataTable } from "@/components/ui/data-table";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { useCopy } from "@/lib/use-copy";
import { OpsKitError } from "@/app/approvals/_api/client";
import { explainError, explainKey } from "@/app/approvals/_api/errors";
import type { ApproverKind, Template, TemplateStep, TemplateVersion } from "@/app/approvals/_api/types";
import { QUERY_KEY, useOpsKit } from "@/app/approvals/_kit";
import { useQueryClient } from "@tanstack/react-query";
import { fmtTime } from "@/lib/utils";
import { FLOWS_COPY } from "../copy";

const KINDS: ApproverKind[] = ["PERM", "ROLE", "GROUP", "MEMBER", "FIELD"];

export default function ApprovalFlowEditPage() {
  return <Suspense><Editor /></Suspense>;
}

function Editor() {
  const c = useCopy(FLOWS_COPY);
  const api = useOpsKit();
  const { locale } = useI18n();
  const code = useSearchParams().get("code") ?? "";
  const q = useQuery({ queryKey: [QUERY_KEY, "template", code], queryFn: () => api.template(code), enabled: !!code });
  const back = <Link href="/approval-flows" className="txt-caption text-primary-ink hover:underline">{c.back}</Link>;
  if (q.isPending) return <div className="space-y-3"><Skeleton className="h-6 w-1/3" /><Skeleton className="h-64 w-full" /></div>;
  if (q.error) return <ErrorState error={new Error(explainError(q.error, locale))} onRetry={() => void q.refetch()} />;
  return (
    <div>
      <TabHeader tabs={[{ key: "flow", label: q.data.name?.[locale] ?? q.data.name?.zh ?? code }]} value="flow" onChange={() => {}} desc={code} action={back} />
      <Form key={code} initial={q.data} />
      <Versions code={code} />
    </div>
  );
}

function Form({ initial }: { initial: Template }) {
  const c = useCopy(FLOWS_COPY);
  const api = useOpsKit();
  const qc = useQueryClient();
  const { locale } = useI18n();
  const [t, setT] = React.useState<Template>(() => structuredClone(initial));
  const [errors, setErrors] = React.useState<{ path: string; error: string }[]>([]);
  const [saving, setSaving] = React.useState(false);
  const lang = locale === "en" ? "en" : "zh";
  const setStep = (i: number, patch: Partial<TemplateStep>) => setT((x) => ({ ...x, steps: x.steps.map((s, j) => (j === i ? { ...s, ...patch } : s)) }));
  const move = (i: number, d: -1 | 1) => setT((x) => {
    const steps = [...x.steps]; const j = i + d;
    if (j < 0 || j >= steps.length) return x;
    [steps[i], steps[j]] = [steps[j], steps[i]];
    return { ...x, steps };
  });
  const add = (type: "APPROVAL" | "ACTION") => setT((x) => ({
    ...x,
    steps: [...x.steps, type === "APPROVAL"
      ? { code: uniqueCode(x.steps, "review"), type, name: { zh: c.stepApproval }, approver: { kind: "PERM", value: "" }, mode: "ANY" }
      : { code: uniqueCode(x.steps, "apply"), type, name: { zh: c.stepAction }, action: { code: "", timeoutMinutes: 10 } }],
  }));
  const errorsAt = (prefix: string) => errors.filter((e) => e.path === prefix || e.path.startsWith(`${prefix}.`) || e.path.startsWith(`${prefix}[`));

  const save = async () => {
    setSaving(true);
    setErrors([]);
    try {
      const r = await api.saveTemplate(t.code, t);
      notify.success(!r.created ? c.unchanged
        : r.runningOnOlderVersions ? c.savedOld.replace("{version}", r.version).replace("{n}", String(r.runningOnOlderVersions))
        : c.saved.replace("{version}", r.version));
      await qc.invalidateQueries({ queryKey: [QUERY_KEY] });
    } catch (e) {
      if (e instanceof OpsKitError && e.fieldErrors.length) setErrors(e.fieldErrors);
      notify.error(explainError(e, locale));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <section className="space-y-3">
        <h2 className="txt-heading">{c.sectionBasic}</h2>
        <label className="block max-w-md space-y-1">
          <span className="txt-caption text-muted-foreground">{c.name}</span>
          <Input value={t.name?.[lang] ?? ""} onChange={(e) => setT((x) => ({ ...x, name: { ...x.name, [lang]: e.target.value } }))} invalid={errorsAt("name").length > 0} />
        </label>
      </section>

      <section className="space-y-3">
        <h2 className="txt-heading">{c.sectionSteps}</h2>
        {t.steps.map((s, i) => {
          const isAction = s.type === "ACTION";
          const errs = errorsAt(`steps[${i}]`);
          return (
            <div key={`${s.code}:${i}`} className={`space-y-3 rounded-card border p-4 ${errs.length ? "border-destructive" : ""}`}>
              <div className="flex items-center justify-between gap-2">
                <span className="txt-body font-semibold">{i + 1}. {isAction ? c.stepAction : c.stepApproval} <code className="txt-caption text-muted-foreground">{s.code}</code></span>
                <span className="flex gap-1">
                  <Button size="sm" variant="ghost" disabled={i === 0} onClick={() => move(i, -1)}>{c.moveUp}</Button>
                  <Button size="sm" variant="ghost" disabled={i === t.steps.length - 1} onClick={() => move(i, 1)}>{c.moveDown}</Button>
                  <Button size="sm" variant="ghost" onClick={() => setT((x) => ({ ...x, steps: x.steps.filter((_, j) => j !== i) }))}>{c.remove}</Button>
                </span>
              </div>
              <div className="grid gap-3 md:grid-cols-3">
                <Labeled label={c.stepName}>
                  <Input value={s.name?.[lang] ?? ""} onChange={(e) => setStep(i, { name: { ...s.name, [lang]: e.target.value } })} />
                </Labeled>
                {isAction ? <>
                  <Labeled label={c.actionCode}>
                    <Input value={s.action?.code ?? ""} readOnly={!!initial.steps.find((x) => x.code === s.code && x.type === "ACTION")}
                      onChange={(e) => setStep(i, { action: { ...s.action, code: e.target.value } })} />
                  </Labeled>
                  <Labeled label={c.timeoutMinutes}>
                    <Input type="number" min={1} value={s.action?.timeoutMinutes ?? 10}
                      onChange={(e) => setStep(i, { action: { code: s.action?.code ?? "", timeoutMinutes: Number(e.target.value) } })} />
                  </Labeled>
                </> : <>
                  <Labeled label={c.approverKind}>
                    <Select value={s.approver?.kind ?? "PERM"} onChange={(e) => setStep(i, { approver: { kind: e.target.value as ApproverKind, value: "" } })}>
                      {KINDS.map((k) => <option key={k} value={k}>{(c as Record<string, string>)[`kind_${k}`]}</option>)}
                    </Select>
                  </Labeled>
                  <Labeled label={c.approverValue}>
                    <Input value={Array.isArray(s.approver?.value) ? s.approver?.value.join(",") : s.approver?.value ?? ""}
                      onChange={(e) => setStep(i, { approver: { kind: s.approver?.kind ?? "PERM", value: s.approver?.kind === "MEMBER" ? e.target.value.split(",").map((v) => v.trim()).filter(Boolean) : e.target.value } })} />
                  </Labeled>
                  <Labeled label={c.mode}>
                    <Select value={s.mode ?? "ANY"} onChange={(e) => setStep(i, { mode: e.target.value as "ANY" | "ALL" })}>
                      <option value="ANY">{c.mode_ANY}</option>
                      <option value="ALL">{c.mode_ALL}</option>
                    </Select>
                  </Labeled>
                  <Labeled label={c.when}>
                    <Input value={s.when?.field ?? ""} placeholder={c.noCondition}
                      onChange={(e) => setStep(i, { when: e.target.value ? { field: e.target.value, op: s.when?.op ?? "TRUE", value: s.when?.value } : undefined })} />
                  </Labeled>
                  {s.when && <>
                    <Labeled label={c.whenOp}>
                      <Select value={s.when.op} onChange={(e) => setStep(i, { when: { ...s.when!, op: e.target.value as "TRUE" | "EQ" | "IN" } })}>
                        <option value="TRUE">TRUE</option><option value="EQ">EQ</option><option value="IN">IN</option>
                      </Select>
                    </Labeled>
                    {s.when.op !== "TRUE" && <Labeled label={c.whenValue}>
                      <Input value={s.when.value ?? ""} onChange={(e) => setStep(i, { when: { ...s.when!, value: e.target.value } })} />
                    </Labeled>}
                  </>}
                  <Labeled label={c.dueHours}>
                    <Input type="number" min={1} value={s.dueHours ?? ""} onChange={(e) => setStep(i, { dueHours: e.target.value ? Number(e.target.value) : null })} />
                  </Labeled>
                </>}
              </div>
              {errs.length > 0 && <ul className="txt-caption text-destructive">{errs.map((e) => <li key={e.path}>{e.path}: {explainKey(e.error, locale) ?? e.error}</li>)}</ul>}
            </div>
          );
        })}
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => add("APPROVAL")}>{c.addApproval}</Button>
          <Button variant="outline" onClick={() => add("ACTION")}>{c.addAction}</Button>
        </div>
      </section>

      <section className="max-w-md space-y-3">
        <h2 className="txt-heading">{c.sectionRules}</h2>
        <SwitchField label={c.selfApproval} desc={c.selfApprovalDesc} checked={!!t.rules?.selfApproval}
          onChange={(v) => setT((x) => ({ ...x, rules: { ...x.rules, selfApproval: v } }))} />
        <SwitchField label={c.crossStepExclusive} desc={c.crossStepExclusiveDesc} checked={!!t.rules?.crossStepExclusive}
          onChange={(v) => setT((x) => ({ ...x, rules: { ...x.rules, crossStepExclusive: v } }))} />
      </section>

      <section className="max-w-md space-y-3">
        <Labeled label={c.note}><Input value={t.note ?? ""} onChange={(e) => setT((x) => ({ ...x, note: e.target.value }))} /></Labeled>
        {errors.length > 0 && errorsAt("steps").length < errors.length && (
          <ul className="txt-caption text-destructive">{errors.filter((e) => !e.path.startsWith("steps")).map((e) => <li key={e.path}>{e.path}: {explainKey(e.error, locale) ?? e.error}</li>)}</ul>
        )}
        <Button onClick={() => void save()} disabled={saving}>{c.save}</Button>
      </section>
    </div>
  );
}

function Versions({ code }: { code: string }) {
  const c = useCopy(FLOWS_COPY);
  const api = useOpsKit();
  const q = useQuery({ queryKey: [QUERY_KEY, "template-versions", code], queryFn: () => api.versions(code) });
  return (
    <section className="mt-8 space-y-3">
      <h2 className="txt-heading">{c.sectionVersions}</h2>
      <DataTable compact rows={q.data as TemplateVersion[] | undefined} loading={q.isPending} error={q.error} onRetry={() => void q.refetch()} rowKey={(v) => v.version} empty={c.emptyVersions} columns={[
        { header: c.colVersion, cell: (v) => <span>{v.version}{v.current && <span className="ml-2 txt-caption text-primary-ink">{c.current}</span>}</span> },
        { header: c.versionAt, cell: (v) => fmtTime(v.atMs) },
        { header: c.versionBy, cell: (v) => v.by ?? "—" },
        { header: c.versionChannel, cell: (v) => (c as Record<string, string>)[`channel_${v.channel}`] ?? v.channel ?? "—" },
        { header: c.note, cell: (v) => v.note ?? "—" },
      ]} />
    </section>
  );
}

function Labeled({ label, children }: { label: string; children: React.ReactNode }) {
  return <label className="block space-y-1"><span className="txt-caption text-muted-foreground">{label}</span>{children}</label>;
}

function uniqueCode(steps: TemplateStep[], base: string): string {
  let n = 1;
  let code = base;
  while (steps.some((s) => s.code === code)) code = `${base}_${++n}`;
  return code;
}
