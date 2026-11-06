"use client";

// 商城商品分类：最多 3 级的分类树。商品只能挂在启用中的分类上（任意一级）。
//
// 规则在后端（commerce CategoryService）与 mock 同构强制：上级必须启用、不能挂到自己的下级、
// 移动时整棵子树一起算深度；停用前要先停用下级、且不能还有在售商品挂着。
// 编码建后不可改（商品与已落单快照都按编码引用），名字随时可改。
import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { useLocaleStore } from "@/lib/stores/locale";
import type { MallCategoryLocale, MallCategoryView } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { HelpNote } from "@/components/ui/help-note";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";
import { Textarea } from "@/components/ui/textarea";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { CATEGORY_MAX_DEPTH, categoryName, categoryTree, descendantsOf, showErr, type MallCopy } from "./shared";

const LOCALES: MallCategoryLocale[] = ["zh-CN", "zh-TW", "en"];

interface CatForm {
  creating: boolean; categoryCode: string; parentCode: string; names: Record<MallCategoryLocale, string>;
  icon: string; sortOrder: string; enabled: boolean; expectedVersion: number; reason: string;
}

const blank = (parentCode = ""): CatForm => ({ creating: true, categoryCode: "", parentCode, names: { "zh-CN": "", "zh-TW": "", en: "" },
  icon: "", sortOrder: "0", enabled: true, expectedVersion: 0, reason: "" });

export function CategoriesTab({ c, canEdit }: { c: MallCopy; canEdit: boolean }) {
  const locale = useLocaleStore((s) => s.locale);
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ["mall-categories"], queryFn: () => api.mallCategories() });
  const { confirm, dialog } = useConfirm();
  const [form, setForm] = React.useState<CatForm | null>(null);
  const rows = React.useMemo(() => categoryTree(list.data ?? []), [list.data]);
  const invalidate = () => ["mall-categories", "mall-catalog", "mall-products"].forEach((k) => qc.invalidateQueries({ queryKey: [k] }));

  const body = (f: CatForm) => ({
    parentCode: f.parentCode || null,
    names: Object.fromEntries(LOCALES.filter((l) => f.names[l].trim()).map((l) => [l, f.names[l].trim()])),
    icon: f.icon.trim() || null, sortOrder: Number(f.sortOrder) || 0, enabled: f.enabled,
    expectedVersion: f.expectedVersion, reason: f.reason.trim(),
  });

  const save = useMutation({
    mutationFn: (f: CatForm) => f.creating
      ? api.createMallCategory({ ...body(f), categoryCode: f.categoryCode.trim() })
      : api.updateMallCategory(f.categoryCode, body(f)),
    onSuccess: () => { invalidate(); setForm(null); notify.success(c.toastSaved); },
  });

  const edit = (r: MallCategoryView) => setForm({
    creating: false, categoryCode: r.categoryCode, parentCode: r.parentCode ?? "",
    names: { "zh-CN": r.names["zh-CN"] ?? "", "zh-TW": r.names["zh-TW"] ?? "", en: r.names.en ?? "" },
    icon: r.icon ?? "", sortOrder: String(r.sortOrder), enabled: r.status === "ENABLED", expectedVersion: r.version, reason: "",
  });

  // 走 mutation 而不是裸调用：失败（CATEGORY_HAS_CHILDREN / CATEGORY_IN_USE…）才会经全局 MutationCache 弹出人话
  const setEnabled = useMutation({
    mutationFn: ({ r, reason }: { r: MallCategoryView; reason: string }) => api.updateMallCategory(r.categoryCode, {
      parentCode: r.parentCode, names: r.names, icon: r.icon, sortOrder: r.sortOrder, enabled: r.status !== "ENABLED",
      expectedVersion: r.version, reason }),
    onSuccess: () => { invalidate(); notify.success(c.toastSaved); },
  });

  const toggle = (r: MallCategoryView) => {
    const on = r.status === "ENABLED";
    return confirm({
      title: on ? c.catDisableTitle : c.catEnableTitle, desc: on ? c.catDisableDesc : c.catEnableDesc,
      danger: on, requireReason: true,
      action: (reason) => setEnabled.mutateAsync({ r, reason }).then(() => undefined),
    });
  };

  const columns: Column<MallCategoryView>[] = [
    { header: c.colName, cell: (r) => (
      <span style={{ paddingInlineStart: `${(r.level - 1) * 1.25}rem` }} className="inline-flex items-center gap-2">
        {r.level > 1 && <span aria-hidden className="text-muted-foreground">└</span>}
        {categoryName(r.names, locale) ?? r.categoryCode}
      </span>
    ) },
    { header: c.colCategoryCode, cell: (r) => <code className="txt-caption">{r.categoryCode}</code> },
    { header: c.colLevel, numeric: true, cell: (r) => r.level },
    { header: c.colSort, numeric: true, cell: (r) => r.sortOrder },
    { header: c.colStatus, cell: (r) => r.status === "ENABLED" ? <Badge tone="success">{c.enabledOn}</Badge> : <Badge tone="muted">{c.enabledOff}</Badge> },
    { header: c.colVersion, numeric: true, cell: (r) => r.version },
    { header: c.colActions, stickyEnd: "always", cell: (r) => canEdit && (
      <span className="flex gap-2">
        <Button size="sm" variant="outline" onClick={() => edit(r)}>{c.btnEdit}</Button>
        {r.level < CATEGORY_MAX_DEPTH && r.status === "ENABLED" && (
          <Button size="sm" variant="outline" onClick={() => setForm(blank(r.categoryCode))}>{c.btnNewChild}</Button>
        )}
        <Button size="sm" variant="outline" onClick={() => toggle(r)}>{r.status === "ENABLED" ? c.btnDisable : c.btnEnable}</Button>
      </span>
    ) },
  ];

  // 可选上级：启用中、未到最深一级、且不是自己或自己的下级（后端同样会拒，这里先不给选）
  const blocked = form && !form.creating ? new Set([form.categoryCode, ...descendantsOf(list.data ?? [], form.categoryCode)]) : new Set<string>();
  const parents = rows.filter((r) => r.status === "ENABLED" && r.level < CATEGORY_MAX_DEPTH && !blocked.has(r.categoryCode));
  const valid = form && /^[A-Za-z0-9_.:-]{1,32}$/.test(form.categoryCode.trim()) && LOCALES.some((l) => form.names[l].trim())
    && form.reason.trim();

  return (
    <div className="space-y-6">
      {!canEdit && <ReadOnlyNotice what={c.catalogEditWhat} perm="commerce:catalog:edit" note={c.catalogEditNote} />}
      <HelpNote>{c.categoriesHelp}</HelpNote>
      <section>
        <SectionHeader title={c.secCategories} desc={c.secCategoriesDesc}
          summary={canEdit && <Button size="sm" onClick={() => setForm(blank())}>{c.btnNewCategory}</Button>} />
        <DataTable columns={columns} rows={rows} loading={list.isLoading} error={showErr(list.error)} onRetry={() => list.refetch()}
          rowKey={(r) => r.categoryCode} empty={c.categoriesEmpty} />
      </section>
      <Drawer open={!!form} onOpenChange={(o) => !o && setForm(null)} title={form?.creating ? c.btnNewCategory : c.categoryEditTitle}
        footer={<Button disabled={!valid} loading={save.isPending} onClick={() => form && save.mutate(form)}>{c.btnSave}</Button>}>
        {form && (
          <div className="space-y-3">
            {form.creating && <Notice tone="info">{c.categoryCreateNotice}</Notice>}
            <div className="space-y-1">
              <Label htmlFor="cat-code" required>{c.colCategoryCode}</Label>
              <Input id="cat-code" value={form.categoryCode} disabled={!form.creating} placeholder="hardware"
                onChange={(e) => setForm((f) => f && { ...f, categoryCode: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="cat-parent">{c.fieldParent}</Label>
              <Select id="cat-parent" className="w-full" value={form.parentCode}
                onChange={(e) => setForm((f) => f && { ...f, parentCode: e.target.value })}>
                <option value="">{c.parentNone}</option>
                {parents.map((p) => (
                  <option key={p.categoryCode} value={p.categoryCode}>
                    {"　".repeat(p.level - 1)}{categoryName(p.names, locale) ?? p.categoryCode}（{p.categoryCode}）
                  </option>
                ))}
              </Select>
            </div>
            <p className="txt-caption text-muted-foreground">{c.namesHint}</p>
            {LOCALES.map((l) => (
              <div key={l} className="space-y-1">
                <Label htmlFor={`cat-name-${l}`}>{c.fieldCategoryName} · {l}</Label>
                <Input id={`cat-name-${l}`} value={form.names[l]} maxLength={64}
                  onChange={(e) => setForm((f) => f && { ...f, names: { ...f.names, [l]: e.target.value } })} />
              </div>
            ))}
            <div className="space-y-1">
              <Label htmlFor="cat-icon">{c.fieldIcon}</Label>
              <Input id="cat-icon" value={form.icon} maxLength={512} placeholder="https://…"
                onChange={(e) => setForm((f) => f && { ...f, icon: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="cat-sort">{c.colSort}</Label>
              <Input id="cat-sort" inputMode="numeric" value={form.sortOrder} onChange={(e) => setForm((f) => f && { ...f, sortOrder: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="cat-reason" required>{c.fieldReason}</Label>
              <Textarea id="cat-reason" value={form.reason} placeholder={c.reasonPh} onChange={(v) => setForm((f) => f && { ...f, reason: v })} />
            </div>
          </div>
        )}
      </Drawer>
      {dialog}
    </div>
  );
}
