"use client";

// 主页模板配置（矩阵 P-10.1.1）。
//
// 这一页改的东西**同时作用在一批店铺页上**，所以每个动作旁边都摆着"影响多少家店"：
// 引用计数不是装饰，它是运营决定要不要动这个模板的唯一依据。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import { MIN_ENABLED_SECTIONS } from "@/lib/constants";
import type { SectionKey, StoreTemplate, TemplateSection } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { Toolbar } from "@/components/ui/toolbar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { StoresCopy } from "./copy";

type Layout = StoreTemplate["layout"];

interface Form {
  templateNo?: string;
  name: string;
  layout: Layout;
  enabled: boolean;
  isDefault: boolean;
  sections: TemplateSection[];
}

const BLANK_SECTIONS: TemplateSection[] = [
  { key: "BANNER", enabled: true, required: true },
  { key: "NOTICE", enabled: false, required: false },
  { key: "HOT", enabled: true, required: false },
  { key: "CATEGORY", enabled: false, required: false },
  { key: "COUPON", enabled: false, required: false },
  { key: "GROUP", enabled: false, required: false },
];

export function TemplateTab({ c, canEdit }: { c: StoresCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [editing, setEditing] = useState<Form | null>(null);

  const sectionLabel: Record<SectionKey, string> = {
    BANNER: c.secBanner, NOTICE: c.secNotice, HOT: c.secHot,
    CATEGORY: c.secCategory, COUPON: c.secCoupon, GROUP: c.secGroup,
  };
  const layoutLabel: Record<Layout, string> = {
    GRID: c.layoutGrid, LIST: c.layoutList, FEATURE: c.layoutFeature,
  };

  const list = useQuery({ queryKey: ["store-templates"], queryFn: () => api.listStoreTemplates() });
  const done = () => { qc.invalidateQueries({ queryKey: ["store-templates"] }); setEditing(null); };

  const save = useMutation({
    mutationFn: () => api.saveStoreTemplate(editing!),
    onSuccess: () => { done(); notify.success(c.toastTemplateSaved); },
  });

  const toggle = useMutation({
    mutationFn: (v: { templateNo: string; enabled: boolean }) => api.setStoreTemplateEnabled(v.templateNo, v.enabled),
    onSuccess: (t) => {
      qc.invalidateQueries({ queryKey: ["store-templates"] });
      notify.success(t.enabled ? c.toastTemplateEnabled : c.toastTemplateDisabled);
    },
  });

  const columns: Column<StoreTemplate>[] = [
    { header: c.colTemplateName, cell: (t) => t.name },
    { header: c.colLayout, cell: (t) => layoutLabel[t.layout] },
    {
      header: c.colSections,
      cell: (t) => (
        <div className="flex flex-wrap gap-1">
          {t.sections.filter((s) => s.enabled).map((s) => (
            <Badge key={s.key} tone={s.required ? "info" : "muted"}>{sectionLabel[s.key]}</Badge>
          ))}
        </div>
      ),
    },
    {
      header: c.colUsedBy,
      // 引用计数是"能不能动这个模板"的唯一依据，不是灰数字
      cell: (t) => (t.usedByCount > 0 ? <Badge tone="warning">{fill(c.usedByStores, { n: t.usedByCount })}</Badge> : <span className="text-muted-foreground">{fill(c.usedByStores, { n: 0 })}</span>),
      numeric: true,
    },
    {
      header: c.colTemplateStatus,
      cell: (t) => (t.enabled ? <Badge tone="success">{c.templateOn}</Badge> : <span className="text-muted-foreground">{c.templateOff}</span>),
    },
    { header: c.colDefault, cell: (t) => (t.isDefault ? <Badge tone="info">{c.badgeDefault}</Badge> : <span className="text-muted-foreground">{c.none}</span>) },
    { header: c.colUpdatedAt, cell: (t) => `${fmtTime(t.updatedAt)} · ${t.updatedBy}` },
    {
      header: c.colActions,
      cell: (t) => {
        // 停不掉的模板不给「停用」按钮：mock 层也会拒，摆一个必然报错的按钮是在骗人点一次
        const lockedOff = t.isDefault || t.usedByCount > 0;
        return (
          <div className="flex gap-2">
            <Button size="sm" variant="outline" disabled={!canEdit}
              onClick={() => setEditing({ ...t, sections: t.sections.map((s) => ({ ...s })) })}>
              {c.actionEditTemplate}
            </Button>
            {t.enabled ? (
              <Button size="sm" variant="ghost" disabled={!canEdit || lockedOff}
                onClick={async () => {
                  const ok = await confirm({
                    title: c.confirmDisableTitle,
                    desc: fill(c.confirmDisableDesc, { name: t.name }),
                    danger: true,
                  });
                  if (ok) toggle.mutate({ templateNo: t.templateNo, enabled: false });
                }}>
                {c.actionDisable}
              </Button>
            ) : (
              <Button size="sm" variant="ghost" disabled={!canEdit}
                onClick={() => toggle.mutate({ templateNo: t.templateNo, enabled: true })}>
                {c.actionEnable}
              </Button>
            )}
          </div>
        );
      },
    },
  ];

  const enabledCount = editing?.sections.filter((s) => s.enabled).length ?? 0;

  return (
    <>
      <Notice className="mb-3">{fill(c.templateNotice, { n: MIN_ENABLED_SECTIONS })}</Notice>
      <Toolbar
        onAdd={() => setEditing({
          name: "", layout: "GRID", enabled: true, isDefault: false,
          sections: BLANK_SECTIONS.map((s) => ({ ...s })),
        })}
        addLabel={c.actionNewTemplate}
        canAdd={canEdit}
      />
      <DataTable
        columns={columns} rows={list.data} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(t) => t.templateNo}
        empty={c.emptyTemplate}
      />

      <Drawer
        open={!!editing}
        onOpenChange={(o) => !o && setEditing(null)}
        title={editing?.templateNo ? fill(c.editTemplateTitle, { name: editing.name }) : c.newTemplateTitle}
        width="w-[520px]"
        footer={canEdit ? <Button loading={save.isPending} onClick={() => save.mutate()}>{c.btnSaveTemplate}</Button> : null}
      >
        {editing && (
          <div>
            <DrawerSection first title={c.secTemplateBasic}>
              <div className="mb-3 space-y-1">
                <Label htmlFor="tpl-name" required>{c.colTemplateName}</Label>
                <Input id="tpl-name" className="w-full" value={editing.name}
                  onChange={(e) => setEditing((p) => p && { ...p, name: e.target.value })} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="tpl-layout" required>{c.colLayout}</Label>
                <Select id="tpl-layout" className="w-full" value={editing.layout}
                  onChange={(e) => setEditing((p) => p && { ...p, layout: e.target.value as Layout })}>
                  {(Object.keys(layoutLabel) as Layout[]).map((k) => <option key={k} value={k}>{layoutLabel[k]}</option>)}
                </Select>
              </div>
            </DrawerSection>

            <DrawerSection title={c.secSections}>
              <div className="space-y-2">
                {editing.sections.map((s, i) => (
                  <label key={s.key} className="flex items-start gap-2">
                    {/* 必选板块直接禁掉复选框：取消再保存才报错，等于让人白点一次 */}
                    <Checkbox
                      checked={s.enabled}
                      disabled={!canEdit || s.required}
                      onChange={(v) => setEditing((p) => p && {
                        ...p, sections: p.sections.map((x, j) => (j === i ? { ...x, enabled: v === true } : x)),
                      })}
                    />
                    <span>
                      <span className="txt-body">{sectionLabel[s.key]}</span>
                      {s.required && <span className="ml-2 txt-caption text-muted-foreground">{c.sectionRequired}</span>}
                    </span>
                  </label>
                ))}
              </div>
              <p className="mt-3 txt-caption text-muted-foreground">
                {fill(c.sectionCount, { n: enabledCount, min: MIN_ENABLED_SECTIONS })}
              </p>
            </DrawerSection>

            {editing.templateNo && (
              <DrawerSection title={c.secImpact}>
                <Field className="mb-0" label={c.colUsedBy}>
                  {fill(c.usedByStores, { n: list.data?.find((t) => t.templateNo === editing.templateNo)?.usedByCount ?? 0 })}
                </Field>
                <p className="mt-1 txt-caption text-muted-foreground">{c.impactHint}</p>
              </DrawerSection>
            )}
          </div>
        )}
      </Drawer>
      {dialog}
    </>
  );
}
