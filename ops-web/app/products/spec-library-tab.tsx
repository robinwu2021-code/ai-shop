"use client";

// 规格库（V195 四层模型的维护面）—— 接 `/ops/spec-dims`、`/ops/spec-values`。
//
// **通用与专用分成两个页面，不是一个页面加个筛选**：通用维度（颜色、重量、材质）
// 改一条**全站生效**，专用维度（段位、房型、口径）只影响一个类目。混在一张表里，
// 改的人不知道自己动了多大范围 —— 而这正是规格库最容易出事的地方。
//
// 判据不是「用在几个类目」，是「值的含义是否跨类目一致」：锅的黑和手机的黑是同一个黑，
// 所以颜色通用；「段位」只在婴幼儿食品下讲得通，所以专用。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { SpecDim, SpecValue } from "@/lib/types";
import { ArchiveActions, ShowArchivedToggle } from "@/components/archive";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Input, Select } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { Toolbar } from "@/components/ui/toolbar";
import type { ProductsCopy } from "./copy";

type DimForm = {
  dimNo?: string;
  code: string;
  name: string;
  valueType: string;
  unit: string;
  usageType: string;
  sort: string;
};

type ValueForm = {
  valueNo?: string;
  dimNo: string;
  code: string;
  label: string;
  numericValue: string;
  aliases: string;
  sort: string;
};

const EMPTY_DIM: DimForm = {
  code: "", name: "", valueType: "ENUM", unit: "", usageType: "SALE", sort: "100",
};

export function SpecLibraryTab({ c, universal, canEdit }: {
  c: ProductsCopy; universal: boolean; canEdit: boolean;
}) {
  const qc = useQueryClient();
  const [keyword, setKeyword] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [dimForm, setDimForm] = useState<DimForm | null>(null);
  const [valueForm, setValueForm] = useState<ValueForm | null>(null);
  const [openDim, setOpenDim] = useState<string | null>(null);

  const q = { universal, keyword, showArchived };
  const list = useQuery({
    queryKey: ["spec-dims", q],
    queryFn: () => api.listSpecDims(q),
  });
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["spec-dims"] });
    // 类目 × 规格那一页读的是同一份数据 —— 改完维度不刷它，两页会各说各的
    qc.invalidateQueries({ queryKey: ["category-specs"] });
  };

  const saveDim = useMutation({
    mutationFn: () => api.saveSpecDim({
      dimNo: dimForm!.dimNo, code: dimForm!.code.trim(), name: dimForm!.name.trim(),
      valueType: dimForm!.valueType, unit: dimForm!.unit.trim() || null,
      usageType: dimForm!.usageType, universal, sort: Number(dimForm!.sort) || 100,
    }),
    onSuccess: () => { setDimForm(null); invalidate(); notify.success(c.save); },
  });

  const saveValue = useMutation({
    mutationFn: () => api.saveSpecValue({
      valueNo: valueForm!.valueNo, dimNo: valueForm!.dimNo, code: valueForm!.code.trim(),
      label: valueForm!.label.trim(),
      numericValue: valueForm!.numericValue ? Number(valueForm!.numericValue) : null,
      aliases: valueForm!.aliases.split(/[,，\s]+/).filter(Boolean),
      sort: Number(valueForm!.sort) || 100,
    }),
    onSuccess: () => { setValueForm(null); invalidate(); notify.success(c.save); },
  });

  const promote = useMutation({
    mutationFn: (valueNo: string) => api.promoteSpecValue(valueNo),
    onSuccess: () => { invalidate(); notify.success(c.slPromoted); },
  });

  const dims = list.data ?? [];
  const quant = (d: SpecDim) => d.valueType === "QUANT";

  const columns: Column<SpecDim>[] = [
    {
      header: c.slColName,
      cell: (d) => (
        <div>
          <button type="button" className="text-left font-semibold hover:underline"
            onClick={() => canEdit && setDimForm({
              dimNo: d.dimNo, code: d.code, name: d.name, valueType: d.valueType,
              unit: d.unit ?? "", usageType: d.usageType, sort: String(d.sort),
            })}>
            {d.name}
          </button>
          <div className="font-mono text-[11.5px] text-muted-foreground">{d.code}</div>
        </div>
      ),
      width: "12rem",
    },
    {
      header: c.slColType,
      cell: (d) => (
        <div className="flex flex-wrap gap-1.5">
          {/* 量纲维度的值必须有归一量 —— 标出来，填值时才不会奇怪为什么强制填数字 */}
          <Badge tone={quant(d) ? "info" : "muted"}>
            {quant(d) ? `${c.slQuant}${d.unit ? " · " + d.unit : ""}` : c.slEnum}
          </Badge>
          {/* PROP 不进 SKU 笛卡尔积 —— 不标的话运营会以为它也会生成规格 */}
          {d.usageType === "PROP" && <Badge tone="warning">{c.csProp}</Badge>}
        </div>
      ),
      width: "10rem",
    },
    { header: c.slColInUse, cell: (d) => d.inUse, numeric: true, width: "7rem" },
    {
      header: c.slColValues,
      cell: (d) => {
        const open = openDim === d.dimNo;
        return (
          <div className="space-y-2">
            <div className="flex flex-wrap items-center gap-1.5">
              {(open ? d.values : d.values.slice(0, 8)).map((v) => (
                <ValueChip key={v.valueNo} v={v} c={c} canEdit={canEdit}
                  onEdit={() => setValueForm({
                    valueNo: v.valueNo, dimNo: d.dimNo, code: v.code, label: v.label,
                    numericValue: v.numericValue == null ? "" : String(v.numericValue),
                    aliases: v.aliases.join(" "), sort: String(v.sort),
                  })}
                  onPromote={() => promote.mutate(v.valueNo)} />
              ))}
              {d.values.length > 8 && (
                <button type="button" className="text-[12px] text-[var(--primary)] hover:underline"
                  onClick={() => setOpenDim(open ? null : d.dimNo)}>
                  {open ? c.csCollapse : `+${d.values.length - 8}`}
                </button>
              )}
              {canEdit && (
                <button type="button" className="text-[12px] text-[var(--primary)] hover:underline"
                  onClick={() => setValueForm({
                    dimNo: d.dimNo, code: "", label: "", numericValue: "", aliases: "", sort: "100",
                  })}>
                  {c.slAddValue}
                </button>
              )}
            </div>
          </div>
        );
      },
    },
    {
      header: c.tplColStatus,
      cell: (d) => (
        <ArchiveActions archived={d.status === "ARCHIVED"} canWrite={canEdit}
          onArchive={async () => { await api.archiveSpecDim(d.dimNo); invalidate(); }}
          onUnarchive={async () => { await api.unarchiveSpecDim(d.dimNo); invalidate(); }} />
      ),
      width: "9rem",
    },
  ];

  return (
    <>
      <Notice className="mb-3">{universal ? c.slNoticeCommon : c.slNoticeSpecial}</Notice>

      <Toolbar search={keyword} onSearch={setKeyword} searchPlaceholder={c.slSearch}>
        <ShowArchivedToggle checked={showArchived} onChange={setShowArchived} label={c.tplShowArchived} />
        {canEdit && <Button size="sm" onClick={() => setDimForm({ ...EMPTY_DIM })}>{c.slNewDim}</Button>}
      </Toolbar>

      <DataTable
        columns={columns} rows={dims} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(d) => d.dimNo}
        rowClassName={(d) => (d.status === "ARCHIVED" ? "opacity-60" : undefined)}
        empty={c.slEmpty}
      />

      <Drawer open={!!dimForm} onOpenChange={(o) => !o && setDimForm(null)}
        title={dimForm?.dimNo ? c.slEditDim : c.slNewDim} desc={dimForm?.dimNo}
        footer={dimForm ? (
          <Button loading={saveDim.isPending}
            disabled={!dimForm.code.trim() || !dimForm.name.trim()}
            onClick={() => saveDim.mutate()}>{c.save}</Button>
        ) : null}>
        {dimForm && (
          <div className="space-y-4">
            <Field label={c.slFieldName}>
              <Input value={dimForm.name} onChange={(e) => setDimForm({ ...dimForm, name: e.target.value })} />
              <p className="mt-1 text-[12px] text-muted-foreground">{c.slNameHint}</p>
            </Field>
            <Field label={c.slFieldCode}>
              <Input value={dimForm.code} disabled={!!dimForm.dimNo}
                onChange={(e) => setDimForm({ ...dimForm, code: e.target.value.toUpperCase() })} />
              <p className="mt-1 text-[12px] text-muted-foreground">{c.slCodeHint}</p>
            </Field>
            <Field label={c.slFieldValueType}>
              <Select value={dimForm.valueType}
                onChange={(e) => setDimForm({ ...dimForm, valueType: e.target.value })}>
                <option value="ENUM">{c.slEnum}</option>
                <option value="QUANT">{c.slQuant}</option>
              </Select>
              <p className="mt-1 text-[12px] text-muted-foreground">{c.slValueTypeHint}</p>
            </Field>
            {dimForm.valueType === "QUANT" && (
              <Field label={c.slFieldUnit}>
              <Input value={dimForm.unit} onChange={(e) => setDimForm({ ...dimForm, unit: e.target.value })} />
              <p className="mt-1 text-[12px] text-muted-foreground">{c.slUnitHint}</p>
            </Field>
            )}
            <Field label={c.slFieldUsage}>
              <Select value={dimForm.usageType}
                onChange={(e) => setDimForm({ ...dimForm, usageType: e.target.value })}>
                <option value="SALE">{c.slSale}</option>
                <option value="PROP">{c.slProp}</option>
              </Select>
              <p className="mt-1 text-[12px] text-muted-foreground">{c.slUsageHint}</p>
            </Field>
            <Field label={c.slFieldSort}>
              <Input value={dimForm.sort} onChange={(e) => setDimForm({ ...dimForm, sort: e.target.value })} />
            </Field>
          </div>
        )}
      </Drawer>

      <Drawer open={!!valueForm} onOpenChange={(o) => !o && setValueForm(null)}
        title={valueForm?.valueNo ? c.slEditValue : c.slAddValue} desc={valueForm?.valueNo}
        footer={valueForm ? (
          <Button loading={saveValue.isPending}
            disabled={!valueForm.code.trim() || !valueForm.label.trim()}
            onClick={() => saveValue.mutate()}>{c.save}</Button>
        ) : null}>
        {valueForm && (
          <div className="space-y-4">
            <Field label={c.slFieldLabel}>
              <Input value={valueForm.label}
                onChange={(e) => setValueForm({ ...valueForm, label: e.target.value })} />
              <p className="mt-1 text-[12px] text-muted-foreground">{c.slLabelHint}</p>
            </Field>
            <Field label={c.slFieldCode}>
              <Input value={valueForm.code} disabled={!!valueForm.valueNo}
                onChange={(e) => setValueForm({ ...valueForm, code: e.target.value.toUpperCase() })} />
              <p className="mt-1 text-[12px] text-muted-foreground">{c.slValueCodeHint}</p>
            </Field>
            <Field label={c.slFieldNumeric}>
              <Input value={valueForm.numericValue}
                onChange={(e) => setValueForm({ ...valueForm, numericValue: e.target.value })} />
              <p className="mt-1 text-[12px] text-muted-foreground">{c.slNumericHint}</p>
            </Field>
            <Field label={c.slFieldAliases}>
              <Input value={valueForm.aliases}
                onChange={(e) => setValueForm({ ...valueForm, aliases: e.target.value })} />
              <p className="mt-1 text-[12px] text-muted-foreground">{c.slAliasesHint}</p>
            </Field>
            <Field label={c.slFieldSort}>
              <Input value={valueForm.sort}
                onChange={(e) => setValueForm({ ...valueForm, sort: e.target.value })} />
            </Field>
          </div>
        )}
      </Drawer>
    </>
  );
}

/** 一个值：文案 + 归一量 + 码。商家自建的标出来，并给「提升为平台值」 */
function ValueChip({ v, c, canEdit, onEdit, onPromote }: {
  v: SpecValue; c: ProductsCopy; canEdit: boolean; onEdit: () => void; onPromote: () => void;
}) {
  const mine = v.scope === "MERCHANT";
  return (
    <span className={`inline-flex items-center gap-1 rounded-chip px-2 py-0.5 text-[12px]
      ${v.status === "ARCHIVED" ? "opacity-45 line-through" : ""}
      ${mine ? "bg-warning-tint" : "bg-muted"}`}>
      <button type="button" className="hover:underline" onClick={() => canEdit && onEdit()}>
        {v.label}
      </button>
      {v.numericValue != null && (
        <span className="tabular-nums text-muted-foreground">{v.numericValue}{v.numericUnit}</span>
      )}
      <span className="font-mono text-[11px] text-muted-foreground">{v.code}</span>
      {mine && (
        // 商家自有值：用的店多了就该进公共值池 —— 提升只改 scope，编号不变，商品不用重建
        <button type="button" className="text-[11px] text-[var(--primary)] hover:underline"
          title={c.slPromoteHint} onClick={onPromote}>
          {c.slPromote}
          {v.merchantCount > 0 && <span className="tabular-nums"> {v.merchantCount}</span>}
        </button>
      )}
    </span>
  );
}
