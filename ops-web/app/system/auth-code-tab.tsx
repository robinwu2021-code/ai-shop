"use client";

// 授权码字典（TDD-一期主数据收敛 阶段二）—— **已接真后端** `/ops/auth-codes/**`。
//
// 它与「商家 · 类目授权」是同一个机制的两半，刻意分在两个页面、两套权限：
//   这里  = 定义门槛（一共有哪些证、各要什么资质）→ 影响全平台
//   那边  = 发证（给这家店哪几张）              → 影响一家店
//
// 在此之前授权码只能靠数据库迁移增删 —— 一期收敛要加 PACKAGED_FOOD、停
// SERVICE_REPAIR，全得改代码发版。等于把「平台升级」永久绑在工程排期上。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { AuthCodeAdmin } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import type { SystemCopy } from "./copy";

/** 新建时的空表单。`code` 只有新建能填 —— 建成之后改它等于换一张证 */
const EMPTY = { code: "", name: "", requiredQualification: "", sort: 0 };

export function AuthCodeTab({ c, canWrite }: { c: SystemCopy; canWrite: boolean }) {
  const qc = useQueryClient();
  const [current, setCurrent] = useState<AuthCodeAdmin | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState(EMPTY);
  const [reason, setReason] = useState("");

  const list = useQuery({ queryKey: ["auth-codes"], queryFn: () => api.listAuthCodeDict() });
  const invalidate = () => qc.invalidateQueries({ queryKey: ["auth-codes"] });
  const close = () => { setCurrent(null); setCreating(false); setReason(""); };

  const save = useMutation({
    mutationFn: () => api.saveAuthCodeDict({
      code: form.code.trim(),
      name: form.name.trim(),
      requiredQualification: form.requiredQualification.trim() || undefined,
      sort: Number(form.sort) || 0,
    }),
    onSuccess: () => { invalidate(); close(); notify.success(c.acSaved); },
  });

  const enabled = useMutation({
    mutationFn: (v: { code: string; enabled: boolean }) =>
      api.setAuthCodeDictEnabled(v.code, v.enabled, reason),
    onSuccess: (row) => { invalidate(); setCurrent(row); notify.success(c.acSaved); },
  });

  const open = (row: AuthCodeAdmin) => {
    setCurrent(row);
    setCreating(false);
    setReason("");
    setForm({
      code: row.code, name: row.name,
      requiredQualification: row.requiredQualification ?? "", sort: row.sort,
    });
  };

  const columns: Column<AuthCodeAdmin>[] = [
    { header: c.acColCode, cell: (r) => <code className="txt-caption">{r.code}</code>, align: "start" },
    { header: c.acColName, cell: (r) => r.name },
    // 空 = 无证件要求，不是漏填。显示成「—」会让人以为这里还没配
    { header: c.acColQual, cell: (r) => r.requiredQualification ?? <span className="text-muted-foreground">{c.acNoQual}</span> },
    {
      header: c.acColEnabled,
      cell: (r) => (r.enabled ? <Badge tone="success">{c.acEnabled}</Badge> : <Badge>{c.acDisabled}</Badge>),
    },
    // 两个计数就是「敢不敢动这个码」的全部依据
    { header: c.acColMerchants, cell: (r) => r.merchantCount, numeric: true },
    { header: c.acColCategories, cell: (r) => r.categoryCount, numeric: true },
    {
      header: c.acColActions,
      cell: (r) => (
        <Button size="sm" variant="outline" onClick={() => open(r)}>{c.acConfigure}</Button>
      ),
    },
  ];

  return (
    <>
      <Notice className="mb-3">{c.acNotice}</Notice>

      {canWrite && (
        <div className="mb-3">
          <Button
            variant="outline"
            onClick={() => { setCreating(true); setCurrent(null); setForm(EMPTY); setReason(""); }}
          >
            {c.acNew}
          </Button>
        </div>
      )}

      <DataTable
        columns={columns}
        rows={list.data}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(r) => r.code}
      />

      <Drawer
        open={!!current || creating}
        onOpenChange={(o) => !o && close()}
        title={creating ? c.acNew : (current?.name ?? "")}
      >
        {(current || creating) && (
          <>
            {current && (
              <DrawerSection title={c.acSectionNow}>
                <FieldGrid>
                  <Field label={c.acColCode}>{current.code}</Field>
                  <Field label={c.acColEnabled}>{current.enabled ? c.acEnabled : c.acDisabled}</Field>
                  <Field label={c.acColMerchants}>{current.merchantCount}</Field>
                  <Field label={c.acColCategories}>{current.categoryCount}</Field>
                </FieldGrid>
              </DrawerSection>
            )}

            {canWrite && (
              <DrawerSection title={creating ? c.acNew : c.acSectionEdit}>
                <div className="space-y-1">
                  <Label htmlFor="ac-code" required>{c.acFieldCode}</Label>
                  <Input
                    id="ac-code" value={form.code} disabled={!creating}
                    placeholder={c.acFieldCodePh}
                    onChange={(e) => setForm((p) => ({ ...p, code: e.target.value }))}
                  />
                  {!creating && <p className="txt-caption text-muted-foreground">{c.acCodeLocked}</p>}
                </div>
                <div className="mt-3 space-y-1">
                  <Label htmlFor="ac-name" required>{c.acFieldName}</Label>
                  <Input id="ac-name" value={form.name}
                    onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} />
                </div>
                <div className="mt-3 space-y-1">
                  <Label htmlFor="ac-qual">{c.acFieldQual}</Label>
                  <Input id="ac-qual" value={form.requiredQualification} placeholder={c.acFieldQualPh}
                    onChange={(e) => setForm((p) => ({ ...p, requiredQualification: e.target.value }))} />
                </div>
                <div className="mt-3 space-y-1">
                  <Label htmlFor="ac-sort">{c.acFieldSort}</Label>
                  <Input id="ac-sort" className="w-24" value={String(form.sort)}
                    onChange={(e) => setForm((p) => ({ ...p, sort: Number(e.target.value) || 0 }))} />
                </div>

                <div className="mt-4">
                  <Button onClick={() => save.mutate()} disabled={save.isPending}>{c.acSave}</Button>
                </div>

                {current && (
                  <>
                    {/* 原因放在开关**上面**：改完再补原因，多半就不补了 */}
                    <div className="mt-6 space-y-1">
                      <Label>{c.acReasonLabel}</Label>
                      <Textarea value={reason} onChange={(v) => setReason(v)} placeholder={c.acReasonPh} />
                    </div>
                    <div className="mt-3">
                      <Button
                        variant="outline"
                        onClick={() => enabled.mutate({ code: current.code, enabled: !current.enabled })}
                      >
                        {current.enabled ? c.acDisableAct : c.acEnableAct}
                      </Button>
                    </div>
                    <Notice className="mt-3">{c.acDisableNote}</Notice>
                  </>
                )}
              </DrawerSection>
            )}
          </>
        )}
      </Drawer>
    </>
  );
}
