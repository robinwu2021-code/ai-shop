"use client";

// 运费模板与超区（矩阵 P-5.2.3）。
//
// 超区规则与运费模板同页，因为它们是同一个决定的两面：
// 「这单收多少运费」与「这个地址到底发不发」——分成两页配，必然出现
// 模板算得出运费、却根本不该配送的地址。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { money } from "@/lib/utils";
import { MINOR_UNIT } from "@/lib/constants";
import type { FreightTemplate, OutOfRangeAction, OutOfRangeRule } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { HelpNote } from "@/components/ui/help-note";
import { Toolbar } from "@/components/ui/toolbar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { FulfillmentCopy } from "./copy";

/** 表单里金额用「元」，落库前换回分 —— 让运营输入 8 而不是 800。 */
const toMinor = (yuan: string) => Math.round(Number(yuan) * MINOR_UNIT);
const toYuan = (minor: number) => String(minor / MINOR_UNIT);

interface Form {
  templateNo?: string;
  name: string;
  firstWeightGram: string;
  firstFee: string;
  addWeightGram: string;
  addFee: string;
  freeThreshold: string;
  isDefault: boolean;
  outOfRange: OutOfRangeRule[];
}

const blank: Form = {
  name: "", firstWeightGram: "1000", firstFee: "8",
  addWeightGram: "500", addFee: "2", freeThreshold: "0",
  isDefault: false, outOfRange: [],
};

const toForm = (t: FreightTemplate): Form => ({
  templateNo: t.templateNo, name: t.name,
  firstWeightGram: String(t.firstWeightGram), firstFee: toYuan(t.firstFee),
  addWeightGram: String(t.addWeightGram), addFee: toYuan(t.addFee),
  freeThreshold: toYuan(t.freeThreshold), isDefault: t.isDefault,
  outOfRange: t.outOfRange.map((r) => ({ ...r })),
});

export function FreightTab({ c, canEdit }: { c: FulfillmentCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [editing, setEditing] = useState<Form | null>(null);

  const list = useQuery({ queryKey: ["freight-templates"], queryFn: () => api.listFreightTemplates() });
  const done = () => { qc.invalidateQueries({ queryKey: ["freight-templates"] }); setEditing(null); };

  const save = useMutation({
    mutationFn: () =>
      api.saveFreightTemplate({
        templateNo: editing!.templateNo,
        name: editing!.name,
        firstWeightGram: Number(editing!.firstWeightGram),
        firstFee: toMinor(editing!.firstFee),
        addWeightGram: Number(editing!.addWeightGram),
        addFee: toMinor(editing!.addFee),
        freeThreshold: toMinor(editing!.freeThreshold),
        isDefault: editing!.isDefault,
        outOfRange: editing!.outOfRange,
      }),
    onSuccess: () => { done(); notify.success(c.toastTemplateSaved); },
  });

  // 归档而不是删除（G1）：硬删会把历史订单的运费依据一起抹掉，
  // 之后谁也说不清那单当时为什么收了 8 元。
  const archive = useMutation({
    mutationFn: (no: string) => api.archiveFreightTemplate(no),
    onSuccess: () => { done(); notify.success(c.toastTemplateArchived); },
  });

  const setRule = (i: number, patch: Partial<OutOfRangeRule>) =>
    setEditing((p) => p && { ...p, outOfRange: p.outOfRange.map((r, j) => (j === i ? { ...r, ...patch } : r)) });

  const columns: Column<FreightTemplate>[] = [
    { header: c.colTemplateName, cell: (t) => t.name },
    {
      header: c.colFirst,
      cell: (t) => fill(c.weightFee, { g: t.firstWeightGram, fee: money(t.firstFee) }),
      numeric: true, align: "start",
    },
    {
      header: c.colAdd,
      cell: (t) => fill(c.weightFee, { g: t.addWeightGram, fee: money(t.addFee) }),
      numeric: true, align: "start",
    },
    {
      header: c.colFreeThreshold,
      // 0 与非 0 是两种策略，不能都渲染成一个数字
      cell: (t) => (t.freeThreshold === 0 ? <span className="text-muted-foreground">{c.noFreeShipping}</span> : money(t.freeThreshold)),
      numeric: true,
    },
    { header: c.colOutOfRange, cell: (t) => fill(c.outOfRangeCount, { n: t.outOfRange.length }), numeric: true },
    { header: c.colDefault, cell: (t) => (t.isDefault ? <Badge tone="info">{c.badgeDefault}</Badge> : <span className="text-muted-foreground">{c.none}</span>) },
    {
      header: c.colActions,
      cell: (t) => (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setEditing(toForm(t))}>{c.actionEdit}</Button>
          {/* 默认模板不给删除按钮：mock 层也会拒绝，但摆一个必然报错的按钮是在骗人点一次 */}
          {canEdit && !t.isDefault && (
            <Button size="sm" variant="ghost"
              onClick={async () => {
                const ok = await confirm({
                  title: c.confirmDeleteTitle,
                  desc: fill(c.confirmDeleteDesc, { name: t.name }),
                  danger: true, confirmText: c.actionArchive,
                });
                if (ok) archive.mutate(t.templateNo);
              }}>
              {c.actionArchive}
            </Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <>
      <HelpNote className="mb-3">{c.freightNotice}</HelpNote>
      <Toolbar onAdd={() => setEditing({ ...blank })} addLabel={c.actionNewTemplate} canAdd={canEdit} />
      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(t) => t.templateNo}
        empty={c.emptyTemplate}
      />

      <Drawer
        open={!!editing}
        onOpenChange={(o) => !o && setEditing(null)}
        title={editing?.templateNo ? fill(c.editTemplateTitle, { name: editing.name }) : c.newTemplateTitle}
        width="w-[560px]"
        footer={canEdit ? <Button loading={save.isPending} onClick={() => save.mutate()}>{c.btnSaveTemplate}</Button> : null}
      >
        {editing && (
          <div>
            <DrawerSection first title={c.secTemplateBasic}>
              <div className="mb-3 space-y-1">
                <Label htmlFor="ft-name" required>{c.colTemplateName}</Label>
                <Input id="ft-name" className="w-full" value={editing.name} disabled={!canEdit}
                  onChange={(e) => setEditing((p) => p && { ...p, name: e.target.value })} />
              </div>
              <FieldGrid>
                <div className="mb-3 space-y-1">
                  <Label htmlFor="ft-fw" required>{c.fieldFirstWeight}</Label>
                  <Input id="ft-fw" className="w-full" value={editing.firstWeightGram} disabled={!canEdit}
                    onChange={(e) => setEditing((p) => p && { ...p, firstWeightGram: e.target.value })} />
                </div>
                <div className="mb-3 space-y-1">
                  <Label htmlFor="ft-ff" required>{c.fieldFirstFee}</Label>
                  <Input id="ft-ff" className="w-full" value={editing.firstFee} disabled={!canEdit}
                    onChange={(e) => setEditing((p) => p && { ...p, firstFee: e.target.value })} />
                </div>
                <div className="mb-3 space-y-1">
                  <Label htmlFor="ft-aw" required>{c.fieldAddWeight}</Label>
                  <Input id="ft-aw" className="w-full" value={editing.addWeightGram} disabled={!canEdit}
                    onChange={(e) => setEditing((p) => p && { ...p, addWeightGram: e.target.value })} />
                </div>
                <div className="mb-3 space-y-1">
                  <Label htmlFor="ft-af" required>{c.fieldAddFee}</Label>
                  <Input id="ft-af" className="w-full" value={editing.addFee} disabled={!canEdit}
                    onChange={(e) => setEditing((p) => p && { ...p, addFee: e.target.value })} />
                </div>
              </FieldGrid>
              <div className="space-y-1">
                <Label htmlFor="ft-free">{c.fieldFreeThreshold}</Label>
                <Input id="ft-free" className="w-full" value={editing.freeThreshold} disabled={!canEdit}
                  onChange={(e) => setEditing((p) => p && { ...p, freeThreshold: e.target.value })} />
                <p className="txt-caption text-muted-foreground">{c.freeThresholdHint}</p>
              </div>
            </DrawerSection>

            <DrawerSection title={c.secOutOfRange}>
              <p className="mb-3 txt-caption text-muted-foreground">{c.outOfRangeHint}</p>
              <div className="space-y-2">
                {editing.outOfRange.map((r, i) => (
                  <div key={i} className="flex items-center gap-2">
                    <Input className="flex-1" value={r.region} disabled={!canEdit} aria-label={c.fieldRegion}
                      placeholder={c.regionPlaceholder}
                      onChange={(e) => setRule(i, { region: e.target.value })} />
                    <Select className="w-32" value={r.action} disabled={!canEdit} aria-label={c.fieldOutAction}
                      onChange={(e) => {
                        const action = e.target.value as OutOfRangeAction;
                        // 切成「不配送」时把加价额清掉：留着它保存会被拒，那是个只能靠报错发现的坑
                        setRule(i, { action, surcharge: action === "REJECT" ? 0 : r.surcharge });
                      }}>
                      <option value="REJECT">{c.outReject}</option>
                      <option value="SURCHARGE">{c.outSurcharge}</option>
                    </Select>
                    <Input className="w-24" value={toYuan(r.surcharge)} aria-label={c.fieldSurcharge}
                      disabled={!canEdit || r.action === "REJECT"}
                      onChange={(e) => setRule(i, { surcharge: toMinor(e.target.value) })} />
                    <Button size="sm" variant="ghost" disabled={!canEdit}
                      onClick={() => setEditing((p) => p && { ...p, outOfRange: p.outOfRange.filter((_, j) => j !== i) })}>
                      {c.actionRemoveRegion}
                    </Button>
                  </div>
                ))}
              </div>
              <Button className="mt-2" size="sm" variant="outline" disabled={!canEdit}
                onClick={() => setEditing((p) => p && {
                  ...p, outOfRange: [...p.outOfRange, { region: "", action: "REJECT", surcharge: 0 }],
                })}>
                {c.btnAddRegion}
              </Button>
            </DrawerSection>
          </div>
        )}
      </Drawer>
      {dialog}
    </>
  );
}
