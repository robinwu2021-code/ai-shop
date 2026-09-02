"use client";

// 规格模板维护（P-3.4 / E27）—— **已接真后端** `/ops/spec-templates/**`。
//
// 这块此前是断裂的：B-4.4 商家建品时能选平台模板，而平台端**没有任何维护入口** ——
// 表里只有初始化时塞进去的几行，谁也改不了、加不了。三端联动表把它记成「模板是死的」。
//
// 页面上唯一"多余"的东西是每个选项前面的规格编码输入框。它不是元数据洁癖：
// 自由文本下三家店会把同一件事写成「5 斤」「五斤」「2.5kg」，聚合、比价、搜索全部对不上（B-4.5）。
// 没有编码的平台模板与商家自己手输的没有区别，它唯一的作用是让人**以为**规格统一了。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { usePaging } from "@/lib/use-paging";
import type { CategoryTemplate, SpecTemplate, SpecTemplateOption } from "@/lib/types";
import { useCategoryTemplateMap } from "@/components/status";
import { ArchiveActions, ArchivedAt, archivedRowClass, ShowArchivedToggle } from "@/components/archive";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { HelpNote } from "@/components/ui/help-note";
import { Pagination } from "@/components/ui/misc";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { ProductsCopy } from "./copy";

/** 抽屉里的表单态。选项是**整组替换**的，所以直接持有一个数组，不做逐项 diff。 */
type Form = {
  templateNo?: string;
  categoryType: string;
  name: string;
  options: SpecTemplateOption[];
};

const EMPTY: Form = { categoryType: "", name: "", options: [{ code: "", label: "" }] };

export function SpecTemplateTab({ c, canEdit }: { c: ProductsCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const { page, setPage, size, setSize } = usePaging();
  const templateMap = useCategoryTemplateMap();

  const [keyword, setKeyword] = useState("");
  const [categoryType, setCategoryType] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<Form | null>(null);

  const q = { keyword, categoryType, showArchived, page, size };
  const list = useQuery({
    queryKey: ["spec-templates", q],
    queryFn: () => api.listSpecTemplates(q),
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ["spec-templates"] });

  const save = useMutation({
    mutationFn: () =>
      api.saveSpecTemplate({
        templateNo: form!.templateNo,
        // 空串与「不限品类」是同一件事，但只有 undefined 才能让后端认出来 ——
        // 空串会被当成一个名叫 "" 的品类，筛选时一条都匹配不到
        categoryType: form!.categoryType || undefined,
        name: form!.name,
        options: form!.options,
      }),
    onSuccess: () => { invalidate(); setForm(null); notify.success(c.toastTplSaved); },
  });
  const archive = useMutation({
    mutationFn: (no: string) => api.archiveSpecTemplate(no),
    onSuccess: () => { invalidate(); notify.success(c.toastTplArchived); },
  });
  const unarchive = useMutation({
    mutationFn: (no: string) => api.unarchiveSpecTemplate(no),
    onSuccess: () => { invalidate(); notify.success(c.toastTplUnarchived); },
  });

  const categoryOptions = (Object.keys(templateMap) as CategoryTemplate[])
    .map((k) => ({ value: k, label: templateMap[k].label }));

  const columns: Column<SpecTemplate>[] = [
    { header: c.tplColNo, cell: (t) => t.templateNo, numeric: true, align: "start" },
    { header: c.tplColName, cell: (t) => t.name },
    {
      // 「不限品类」与「某个品类」是两回事：前者在所有品类下都会出现在商家的下拉里。
      // 显示成空白会让人以为是漏填了
      header: c.tplColCategory,
      cell: (t) => (t.categoryType
        ? templateMap[t.categoryType]?.label ?? t.categoryType
        : <span className="text-muted-foreground">{c.tplCategoryAny}</span>),
    },
    {
      header: c.tplColOptions,
      className: "whitespace-normal",
      width: "22rem",
      // 编码与文案一起显示：只显示文案的话，这一列看起来就是普通的自由文本，
      // 而编码才是这份模板存在的理由
      cell: (t) => (
        <span className="flex flex-wrap gap-1">
          {t.options.map((o) => (
            <Badge key={o.code} tone="muted">
              <span className="tabular-nums">{o.code}</span> · {o.label}
            </Badge>
          ))}
        </span>
      ),
    },
    {
      header: c.tplColStatus,
      cell: (t) => (t.archivedAt
        ? <Badge tone="warning">{c.tplStatusArchived}</Badge>
        : <Badge tone="success">{c.tplStatusActive}</Badge>),
    },
    { header: c.tplColArchivedAt, cell: (t) => <ArchivedAt at={t.archivedAt} /> },
    {
      header: c.colActions,
      cell: (t) => (
        <ArchiveActions
          archived={!!t.archivedAt}
          canWrite={canEdit}
          onArchive={async () => {
            const ok = await confirm({
              title: fill(c.tplConfirmArchiveTitle, { name: t.name }),
              desc: c.tplConfirmArchiveDesc,
              danger: true, confirmText: c.tplConfirmArchiveOk,
            });
            if (ok) archive.mutate(t.templateNo);
          }}
          onUnarchive={() => unarchive.mutate(t.templateNo)}
          actions={
            <Button size="sm" variant="outline"
              onClick={() => setForm({
                templateNo: t.templateNo,
                categoryType: t.categoryType ?? "",
                name: t.name,
                // 拷贝一份再编辑：直接改 query 缓存里的对象，取消后列表也已经变了
                options: t.options.map((o) => ({ ...o })),
              })}>
              {c.tplEdit}
            </Button>
          }
        />
      ),
    },
  ];

  const setOption = (i: number, patch: Partial<SpecTemplateOption>) =>
    setForm((f) => (f ? { ...f, options: f.options.map((o, k) => (k === i ? { ...o, ...patch } : o)) } : f));

  return (
    <>
      {/*
        类目 × 规格已经搬到自己的页面（并变成可编辑的），这里不再寄居一份只读副本 ——
        两处显示同一件事，改了一处另一处不动，是最容易让人以为「没保存成功」的形状。
      */}
      <HelpNote className="mb-3">{c.tplNotice}</HelpNote>

      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.tplSearch}>
        <FilterSelect aria-label={c.tplFilterCategory} value={categoryType}
          onChange={(v) => { setCategoryType(v); setPage(1); }}
          options={categoryOptions} allLabel={c.tplFilterCategoryAll} />
        <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }}
          label={c.tplShowArchived} />
        {canEdit && <Button size="sm" onClick={() => setForm({ ...EMPTY, options: [{ code: "", label: "" }] })}>{c.tplNew}</Button>}
      </Toolbar>

      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(t) => t.templateNo}
        rowClassName={archivedRowClass}
        empty={c.tplEmpty}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />

      <Drawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        title={form?.templateNo ? c.tplDrawerEdit : c.tplDrawerNew}
        desc={form?.templateNo}
        width="w-[560px]"
        footer={form ? (
          <Button
            loading={save.isPending}
            /*
             * 编码为空就存不下去 —— 这条在前端也拦一道，不是重复校验：
             * 后端拒了之后运营只看到一句红字，而这里能让「哪一行还没填」当场看得见。
             */
            disabled={!form.name.trim() || form.options.length === 0
              || form.options.some((o) => !o.code.trim() || !o.label.trim())}
            onClick={() => save.mutate()}
          >
            {c.save}
          </Button>
        ) : null}
      >
        {form && (
          <div className="space-y-4">
            <div className="space-y-1">
              <Label htmlFor="tpl-name" required>{c.tplFieldName}</Label>
              <Input id="tpl-name" className="w-full" value={form.name} placeholder={c.tplNamePh}
                onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>

            <div className="space-y-1">
              <Label htmlFor="tpl-cat">{c.tplFieldCategory}</Label>
              <Select id="tpl-cat" className="w-full" value={form.categoryType}
                onChange={(e) => setForm({ ...form, categoryType: e.target.value })}>
                <option value="">{c.tplCategoryAny}</option>
                {categoryOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </Select>
            </div>

            <Field label={c.tplFieldOptions}>
              <p className="mb-2 txt-caption text-muted-foreground">{c.tplOptionsHint}</p>
              <div className="space-y-2">
                {form.options.map((o, i) => (
                  <div key={i} className="flex items-center gap-2">
                    <Input className="w-40 tabular-nums" value={o.code} placeholder={c.tplOptionCodePh}
                      onChange={(e) => setOption(i, { code: e.target.value })} />
                    <Input className="flex-1" value={o.label} placeholder={c.tplOptionLabelPh}
                      onChange={(e) => setOption(i, { label: e.target.value })} />
                    <Button size="sm" variant="ghost"
                      // 最后一行不给删：删空之后抽屉里没有任何输入框，看起来像坏了
                      disabled={form.options.length <= 1}
                      onClick={() => setForm({ ...form, options: form.options.filter((_, k) => k !== i) })}>
                      {c.tplRemoveOption}
                    </Button>
                  </div>
                ))}
              </div>
              <Button className="mt-2" size="sm" variant="outline"
                onClick={() => setForm({ ...form, options: [...form.options, { code: "", label: "" }] })}>
                {c.tplAddOption}
              </Button>
            </Field>
          </div>
        )}
      </Drawer>

      {dialog}
    </>
  );
}
