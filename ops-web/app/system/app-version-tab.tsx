"use client";

// App 版本策略（TDD-商户App问题回报与版本 V6）—— 真后端 `/ops/app-versions/**`。
//
// 这一页改的两个数影响面完全不同：
//   · 最新版 —— App 据此提示「有新版本」，不改变任何人能做什么；
//   · 最低支持版 —— 低于它的包**写请求立刻被拒**（10426），全部商户同时受影响。
// 所以改最低支持版要单独的 system:app-version:update（技术运维的 param:update 改不了），
// 并且保存前二次确认、把「低于此版本的商户将无法写入」摆在确认框里。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import type { AppVersionLang, AppVersionPolicy } from "@/lib/types";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { Button } from "@/components/ui/button";
import { Drawer, DrawerSection } from "@/components/ui/drawer";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { SystemCopy } from "./copy";

const LANGS: AppVersionLang[] = ["zh-Hant", "zh-Hans", "en", "ar"];

type Form = { latest: string; minSupported: string; storeUrl: string; notes: Record<AppVersionLang, string> };

const toForm = (r: AppVersionPolicy): Form => ({
  latest: r.latest,
  minSupported: r.minSupported,
  storeUrl: r.storeUrl ?? "",
  notes: Object.fromEntries(LANGS.map((l) => [l, (r.notes[l] ?? []).join("\n")])) as Record<AppVersionLang, string>,
});

export function AppVersionTab({ c, canWrite }: { c: SystemCopy; canWrite: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [current, setCurrent] = useState<AppVersionPolicy | null>(null);
  const [form, setForm] = useState<Form | null>(null);

  const list = useQuery({ queryKey: ["app-versions"], queryFn: () => api.listAppVersions() });

  const save = useMutation({
    mutationFn: (v: { row: AppVersionPolicy; form: Form }) =>
      api.saveAppVersion(v.row.appCode, v.row.platform, {
        latest: v.form.latest.trim(),
        minSupported: v.form.minSupported.trim(),
        storeUrl: v.form.storeUrl.trim() || null,
        // 一行一条；空行丢掉
        notes: Object.fromEntries(LANGS.map((l) => [l, v.form.notes[l].split("\n").map((x) => x.trim()).filter(Boolean)])),
        expectedRevision: v.row.revision,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["app-versions"] });
      setCurrent(null);
      notify.success(c.avSaved);
    },
  });

  async function submit() {
    if (!current || !form) return;
    const minChanged = form.minSupported.trim() !== current.minSupported;
    if (minChanged && form.minSupported.trim()) {
      await confirm({
        title: c.avConfirmTitle,
        desc: fill(c.avConfirmBody, { platform: current.platform, min: form.minSupported.trim() }),
        danger: true,
        action: () => save.mutateAsync({ row: current, form }),
      });
      return;
    }
    save.mutate({ row: current, form });
  }

  const columns: Column<AppVersionPolicy>[] = [
    { header: c.avColPlatform, cell: (r) => r.platform, align: "start" },
    { header: c.avColLatest, cell: (r) => r.latest || <span className="text-muted-foreground">{c.avNotSet}</span> },
    { header: c.avColMin, cell: (r) => r.minSupported || <span className="text-muted-foreground">{c.avNotSet}</span> },
    { header: c.avColStore, cell: (r) => r.storeUrl ?? "—", className: "whitespace-normal break-all" },
    { header: c.avColUpdated, cell: (r) => (r.updatedAt ? `${fmtTime(r.updatedAt)} · ${r.updatedBy ?? "—"}` : "—") },
    {
      header: c.colActions,
      cell: (r) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(r); setForm(toForm(r)); }}>
          {canWrite ? c.avEdit : c.avView}
        </Button>
      ),
    },
  ];

  return (
    <>
      {!canWrite && <ReadOnlyNotice what={c.avReadOnlyWhat} perm="system:app-version:update" className="mb-3" />}
      <HelpNote className="mb-3">{c.avNotice}</HelpNote>
      <DataTable
        columns={columns} rows={list.data} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(r) => `${r.appCode}:${r.platform}`}
        empty={c.avEmpty}
      />

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current ? `${current.appCode} · ${current.platform}` : ""}
        width="w-[520px]"
        footer={canWrite && current ? (
          <Button onClick={() => submit()} disabled={save.isPending}>{c.avSave}</Button>
        ) : null}
      >
        {current && form && (
          <>
            <DrawerSection title={c.avSectionVersion}>
              <div className="space-y-1">
                <Label htmlFor="av-latest">{c.avColLatest}</Label>
                <Input id="av-latest" value={form.latest} disabled={!canWrite} placeholder="0.2.0"
                  onChange={(e) => { const v = e.target.value; setForm((f) => f && { ...f, latest: v }); }} />
              </div>
              <div className="mt-3 space-y-1">
                <Label htmlFor="av-min">{c.avColMin}</Label>
                <Input id="av-min" value={form.minSupported} disabled={!canWrite} placeholder="0.1.0"
                  onChange={(e) => { const v = e.target.value; setForm((f) => f && { ...f, minSupported: v }); }} />
                <p className="txt-caption text-muted-foreground">{c.avMinHint}</p>
              </div>
              <div className="mt-3 space-y-1">
                <Label htmlFor="av-store">{c.avColStore}</Label>
                <Input id="av-store" value={form.storeUrl} disabled={!canWrite} placeholder="https://play.google.com/store/apps/details?id=…"
                  onChange={(e) => { const v = e.target.value; setForm((f) => f && { ...f, storeUrl: v }); }} />
                <p className="txt-caption text-muted-foreground">{c.avStoreHint}</p>
              </div>
            </DrawerSection>
            <DrawerSection title={c.avSectionNotes}>
              <p className="mb-2 txt-caption text-muted-foreground">{c.avNotesHint}</p>
              {LANGS.map((l) => (
                <div key={l} className="mt-3 space-y-1">
                  <Label htmlFor={`av-notes-${l}`} required={l === "zh-Hant" && !!form.latest.trim()}>{l}</Label>
                  <Textarea id={`av-notes-${l}`} value={form.notes[l]} disabled={!canWrite}
                    onChange={(v) => setForm((f) => f && { ...f, notes: { ...f.notes, [l]: v } })} />
                </div>
              ))}
            </DrawerSection>
          </>
        )}
      </Drawer>
      {dialog}
    </>
  );
}
