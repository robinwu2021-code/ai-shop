"use client";

// 素材中心（矩阵 P-15.1）。给商家提供可下载的文案 / 图 / 海报 / 短视频。
//
// 分发范围（15.1.3 / 15.1.4）是**素材行上的字段**，不是另一张表：
// 一份素材投给谁，和这份素材本身是一件事。
//
// 内容审核（P-15.2 种草 / 榜单 / 问答）矩阵标 P1 二期，本批不做，导航里保持待建。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { CONTENTS_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { Material, MaterialKind, MaterialScope } from "@/lib/types";
import { useMaterialKindMap, useMaterialScopeMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
// 内容审核与榜单问答各自成块 —— 与素材库只共用文案表
import { AuditTab } from "./audit-tab";
import { RankTab } from "./rank-tab";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { TabHeader } from "@/components/ui/tab-header";
import { Textarea } from "@/components/ui/textarea";
import { Toolbar } from "@/components/ui/toolbar";

type Copy = (typeof CONTENTS_COPY)["zh"];
const TAB_KEYS = ["materials", "audit", "rank"] as const;

const KIND_OPTIONS = (c: Copy): { value: MaterialKind; label: string }[] => [
  { value: "COPY", label: c.kindCopy },
  { value: "IMAGE", label: c.kindImage },
  { value: "POSTER", label: c.kindPoster },
  { value: "VIDEO", label: c.kindVideo },
];

const SCOPE_OPTIONS = (c: Copy): { value: MaterialScope; label: string; hint: string }[] => [
  { value: "ALL", label: c.scopeAll, hint: c.scopeAllHint },
  { value: "COMMUNITY", label: c.scopeCommunity, hint: c.scopeCommunityHint },
  { value: "MERCHANT", label: c.scopeMerchant, hint: c.scopeMerchantHint },
];

export default function ContentsPage() {
  return <Suspense fallback={null}><ContentsInner /></Suspense>;
}

function ContentsInner() {
  const c = useCopy(CONTENTS_COPY);
  const tabs = useNavTabs("/contents", TAB_KEYS);
  const kindOptions = KIND_OPTIONS(c);
  const scopeOptions = SCOPE_OPTIONS(c);
  const qc = useQueryClient();
  const allow = useCan();

  const [tab, setTab] = usePageTab(tabs);

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [kind, setKind] = useState("");
  const [scope, setScope] = useState("");
  const [editing, setEditing] = useState<Material | null>(null);
  const [form, setForm] = useState<{ title: string; kind: MaterialKind; content: string; scope: MaterialScope; scopeRefs: string }>(
    { title: "", kind: "COPY", content: "", scope: "ALL", scopeRefs: "" },
  );

  const canEdit = allow("content:material:update");
  const canAudit = allow("content:material:audit");
  const kindMap = useMaterialKindMap();
  const scopeMap = useMaterialScopeMap();

  const q = { keyword, kind, scope, page, size };
  const list = useQuery({ queryKey: ["materials", q], queryFn: () => api.listMaterials(q) });

  const invalidate = () => qc.invalidateQueries({ queryKey: ["materials"] });

  const save = useMutation({
    mutationFn: () =>
      api.saveMaterial({
        materialNo: editing?.materialNo ?? "",
        title: form.title,
        kind: form.kind,
        content: form.content,
        scope: form.scope,
        // 逗号分隔 → 数组。范围选了社区/商家却不填对象，服务端会拒绝
        scopeRefs: form.scope === "ALL" ? [] : form.scopeRefs.split(/[,，\s]+/).filter(Boolean),
        langs: editing?.langs ?? [],
      }),
    onSuccess: () => { invalidate(); setEditing(null); notify.success(c.toastSaved); },
  });

  const publish = useMutation({
    mutationFn: (v: { no: string; published: boolean }) => api.setMaterialPublished(v.no, v.published),
    onSuccess: () => { invalidate(); notify.success(c.toastPublished); },
  });

  const openNew = () => {
    setEditing({ materialNo: "", title: "", kind: "COPY", content: "", scope: "ALL", scopeRefs: [], langs: [], published: false, downloads: 0, createdAt: "" });
    setForm({ title: "", kind: "COPY", content: "", scope: "ALL", scopeRefs: "" });
  };

  const columns: Column<Material>[] = [
    { header: c.colNo, cell: (m) => m.materialNo, numeric: true, align: "start" },
    { header: c.colTitle, cell: (m) => m.title, className: "whitespace-normal", width: "16rem" },
    { header: c.colKind, cell: (m) => <StatusBadge map={kindMap} value={m.kind} /> },
    {
      header: c.colContent,
      className: "whitespace-normal",
      width: "22rem",
      cell: (m) => <span className="line-clamp-1 text-muted-foreground">{m.content}</span>,
    },
    {
      header: c.colScope,
      // 范围是这份素材的一部分，不是另一张配置表 —— 所以直接摆在行上
      cell: (m) => (
        <span className="flex items-center gap-1">
          <StatusBadge map={scopeMap} value={m.scope} />
          {m.scopeRefs.length > 0 && <span className="text-muted-foreground">{m.scopeRefs.join("、")}</span>}
        </span>
      ),
    },
    { header: c.colDownloads, cell: (m) => m.downloads, numeric: true },
    { header: c.colCreatedAt, cell: (m) => fmtTime(m.createdAt) },
    {
      header: c.colPublished,
      cell: (m) => (
        <Switch checked={m.published} disabled={!canEdit} aria-label={fill(c.ariaPublish, { title: m.title })}
          onChange={(v) => publish.mutate({ no: m.materialNo, published: v })} />
      ),
    },
    {
      header: c.colActions,
      cell: (m) =>
        canEdit ? (
          <Button size="sm" variant="outline"
            onClick={() => {
              setEditing(m);
              setForm({ title: m.title, kind: m.kind, content: m.content, scope: m.scope, scopeRefs: m.scopeRefs.join("、") });
            }}>
            {c.actionEdit}
          </Button>
        ) : <span className="text-muted-foreground">—</span>,
    },
  ];

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "audit" && (
        <>
          {!canAudit && <ReadOnlyNotice what={c.auditReadOnlyWhat} perm="content:material:audit" className="mb-3" />}
          <AuditTab c={c} canAudit={canAudit} />
        </>
      )}

      {tab === "rank" && (
        <>
          {!canEdit && <ReadOnlyNotice what={c.rankReadOnlyWhat} perm="content:material:update" className="mb-3" />}
          <RankTab c={c} canEdit={canEdit} />
        </>
      )}

      {tab === "materials" && (
      <>
      {!canEdit && <ReadOnlyNotice what={c.readOnlyWhat} perm="content:material:update" note={c.readOnlyNote} className="mb-3" />}

      <Notice className="mb-3">{c.notice}</Notice>

      <Toolbar
        search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }}
        searchPlaceholder={c.searchPlaceholder}
        onAdd={canEdit ? openNew : undefined}
        addLabel={c.addLabel}
      >
        <FilterSelect aria-label={c.filterKind} value={kind} onChange={(v) => { setKind(v); setPage(1); }} options={kindMap} allLabel={c.filterKindAll} />
        <FilterSelect aria-label={c.filterScope} value={scope} onChange={(v) => { setScope(v); setPage(1); }} options={scopeMap} allLabel={c.filterScopeAll} />
      </Toolbar>

      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(m) => m.materialNo}
        empty={c.empty}
        emptyAction={canEdit ? <Button size="sm" onClick={openNew}>{c.addLabel}</Button> : undefined}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />
      </>
      )}

      <Drawer
        open={!!editing}
        onOpenChange={(o) => !o && setEditing(null)}
        title={editing?.materialNo ? c.drawerEdit : c.drawerNew}
        desc={editing?.materialNo || undefined}
        footer={<Button loading={save.isPending} onClick={() => save.mutate()}>{c.save}</Button>}
      >
        <div className="space-y-4">
          <div className="space-y-1">
            <Label htmlFor="ma-title" required>{c.fieldTitle}</Label>
            <Input id="ma-title" className="w-full" value={form.title}
              onChange={(e) => setForm((p) => ({ ...p, title: e.target.value }))} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="ma-kind">{c.fieldKind}</Label>
            <Select id="ma-kind" className="w-full" value={form.kind}
              onChange={(e) => setForm((p) => ({ ...p, kind: e.target.value as MaterialKind }))}>
              {kindOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </Select>
          </div>
          <div className="space-y-1">
            <Label htmlFor="ma-content" required>{c.fieldContent}</Label>
            <Textarea value={form.content} onChange={(v) => setForm((p) => ({ ...p, content: v }))}
              placeholder={form.kind === "COPY" ? c.contentPlaceholderCopy : c.contentPlaceholderFile} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="ma-scope" required>{c.fieldScope}</Label>
            <Select id="ma-scope" className="w-full" value={form.scope}
              onChange={(e) => setForm((p) => ({ ...p, scope: e.target.value as MaterialScope }))}>
              {scopeOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </Select>
            <p className="txt-caption text-muted-foreground">
              {scopeOptions.find((o) => o.value === form.scope)?.hint}
            </p>
          </div>
          {form.scope !== "ALL" && (
            <div className="space-y-1">
              <Label htmlFor="ma-refs" required>{form.scope === "COMMUNITY" ? c.fieldCommunityNo : c.fieldMerchantNo}</Label>
              <Input id="ma-refs" className="w-full" placeholder={form.scope === "COMMUNITY" ? c.refsPlaceholderCommunity : c.refsPlaceholderMerchant}
                value={form.scopeRefs} onChange={(e) => setForm((p) => ({ ...p, scopeRefs: e.target.value }))} />
              <p className="txt-caption text-muted-foreground">{c.refsHint}</p>
            </div>
          )}
        </div>
      </Drawer>
    </div>
  );
}
