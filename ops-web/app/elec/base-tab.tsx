"use client";

// 基础数据（P-19.4）：厂牌与别名。料号认厂牌全靠别名表，这一页是它的增长口。
// 最上面是「认不出的厂牌」—— 上传时落到 UNKNOWN 的写法按出现次数排，带建议，一点补成别名：
// **既有库存当场改认**（后端逐行重认、重算投影），补完这一行就从列表消失。
import Link from "next/link";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { ElecAliasRow, ElecHeaderAliasRow, ElecHeaderAliasScope, ElecMfrRow, ElecUnknownMfrRow } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import type { FormValues } from "@/lib/form-validate";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";
import { Tabs } from "@/components/ui/tabs";
import { Toolbar } from "@/components/ui/toolbar";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import type { ElecCopy } from "./copy";
import { qty, when } from "./fmt";

export function BaseTab({ c }: { c: ElecCopy }) {
  const qc = useQueryClient();
  const [kwInput, setKwInput] = useState("");
  const [kw, setKw] = useState("");
  const [pick, setPick] = useState<Record<string, string>>({});
  const [aliasOf, setAliasOf] = useState<ElecMfrRow | null>(null);
  const [editing, setEditing] = useState<ElecMfrRow | "new" | null>(null);
  const [form, setForm] = useState<FormValues>({});

  const unknown = useQuery({ queryKey: ["elec-unknown"], queryFn: () => api.listElecUnknownMfrs(50) });
  const mfrs = useQuery({ queryKey: ["elec-mfr", kw], queryFn: () => api.listElecMfrs(kw || undefined) });
  const allMfrs = useQuery({ queryKey: ["elec-mfr", ""], queryFn: () => api.listElecMfrs(undefined) });

  const toAlias = useMutation({
    mutationFn: (v: { code: string; alias: string }) => api.addElecAlias(v.code, v.alias),
    onSuccess: (r) => {
      notify.success(fill(c.baseAliasDone, { rows: r.movedRows, parts: r.touchedParts }));
      void qc.invalidateQueries({ queryKey: ["elec-unknown"] });
      void qc.invalidateQueries({ queryKey: ["elec-mfr"] });
      void qc.invalidateQueries({ queryKey: ["elec-alias"] });
    },
    onError: (e) => notify.error((e as Error).message),
  });

  const saveMfr = useMutation({
    mutationFn: () => {
      const req = { mfrCode: String(form.mfrCode ?? "").trim().toUpperCase() || undefined,
        nameEn: String(form.nameEn ?? "").trim(), nameCn: String(form.nameCn ?? "").trim() || undefined };
      return editing === "new" ? api.createElecMfr(req) : api.renameElecMfr((editing as ElecMfrRow).mfrCode, req);
    },
    onSuccess: () => { setEditing(null); notify.success(c.baseMfrSaved); void qc.invalidateQueries({ queryKey: ["elec-mfr"] }); },
    onError: (e) => notify.error((e as Error).message),
  });

  const mfrOptions = (allMfrs.data ?? []).filter((m) => m.mfrCode !== "UNKNOWN" && m.status === "ACTIVE")
    .map((m) => ({ value: m.mfrCode, label: `${m.mfrCode} · ${m.nameCn ?? m.nameEn}` }));

  const unknownCols: Column<ElecUnknownMfrRow>[] = [
    { header: c.baseColRaw, cell: (u) => <div className="font-medium">{u.sample}<div className="font-mono txt-caption text-muted-foreground">{u.aliasNorm}</div></div> },
    { header: c.baseColRows, cell: (u) => qty(u.rowCnt), numeric: true },
    { header: c.baseColSuppliersParts, cell: (u) => (u.rowCnt ? `${u.supplierCnt} · ${u.partCnt}` : "—") },
    {
      // 买家那一侧（询价厂牌选择 §8）：同一种写法两边并成一行，补一次别名两边都认得出
      header: c.baseColRfq,
      cell: (u) => u.rfqLineCnt ? (
        <div>
          {fill(c.baseRfqLines, { n: u.rfqLineCnt, b: u.buyerCnt })}
          {u.sampleRfqNo && (
            <div className="txt-caption">
              <Link className="focus-ring rounded-chip text-primary-ink hover:underline" href={`/elec?tab=rfq&rfq=${encodeURIComponent(u.sampleRfqNo)}`}>
                {c.baseSampleRfq}
              </Link>
            </div>
          )}
        </div>
      ) : "—",
    },
    {
      header: c.baseColSuggest,
      cell: (u) => {
        const code = pick[u.aliasNorm] ?? u.suggestCode ?? "";
        return (
          <div className="flex items-center gap-2">
            <FilterSelect value={code} onChange={(v) => setPick((p) => ({ ...p, [u.aliasNorm]: v }))}
              options={mfrOptions} allLabel={u.suggestCode ? undefined : c.basePickMfr} aria-label={c.basePickMfr} />
            <Button size="sm" disabled={!code || toAlias.isPending}
              onClick={() => toAlias.mutate({ code, alias: u.sample })}>
              {code ? fill(c.baseToAlias, { mfr: code }) : c.baseToAliasNone}
            </Button>
          </div>
        );
      },
    },
  ];

  const mfrCols: Column<ElecMfrRow>[] = [
    { header: c.baseColCode, cell: (m) => <span className="font-mono">{m.mfrCode}</span> },
    { header: c.baseColName, cell: (m) => <div>{m.nameCn ?? m.nameEn}<div className="txt-caption text-muted-foreground">{m.nameEn}</div></div> },
    { header: c.colStatus, cell: (m) => (m.mergedInto ? fill(c.baseMerged, { to: m.mergedInto }) : c.supACTIVE) },
    { header: c.baseColAliases, cell: (m) => qty(m.aliasCnt), numeric: true },
    { header: c.baseColParts, cell: (m) => qty(m.partCnt), numeric: true },
    {
      header: "",
      cell: (m) => m.mfrCode === "UNKNOWN" ? null : (
        <div className="flex gap-1">
          <Button size="sm" variant="ghost" onClick={() => setAliasOf(m)}>{c.baseAliases}</Button>
          <Button size="sm" variant="ghost" onClick={() => {
            setForm({ mfrCode: m.mfrCode, nameEn: m.nameEn, nameCn: m.nameCn ?? "" });
            setEditing(m);
          }}>{c.baseRename}</Button>
        </div>
      ),
    },
  ];

  const fields: FieldDef[] = [
    { key: "mfrCode", label: c.fMfrCode, required: true, readOnlyOnEdit: true, pattern: { re: "^[A-Za-z0-9]{2,32}$", msg: c.fMfrCode } },
    { key: "nameEn", label: c.fNameEn, required: true, maxLength: 64 },
    { key: "nameCn", label: c.fNameCn, maxLength: 64 },
  ];

  return (
    <div className="space-y-8">
      <section className="space-y-3">
        <SectionHeader title={c.baseUnknown} />
        <Notice tone="info">{c.baseUnknownHint}</Notice>
        <DataTable columns={unknownCols} rows={unknown.data} loading={unknown.isLoading} error={unknown.error}
          onRetry={() => unknown.refetch()} empty={c.baseUnknownEmpty} rowKey={(u) => u.aliasNorm} />
      </section>

      <section className="space-y-3">
        <SectionHeader title={c.baseMfrs} />
        <Toolbar onAdd={() => { setForm({}); setEditing("new"); }} addLabel={c.baseAddMfr}>
          <Input className="w-56" value={kwInput} placeholder={c.baseMfrPh}
            onChange={(e) => setKwInput(e.target.value)} onKeyDown={(e) => e.key === "Enter" && setKw(kwInput.trim())} />
        </Toolbar>
        <DataTable columns={mfrCols} rows={mfrs.data} loading={mfrs.isLoading} error={mfrs.error}
          onRetry={() => mfrs.refetch()} empty={c.baseMfrEmpty} rowKey={(m) => m.mfrCode} />
      </section>

      <HeaderAliases c={c} />

      <FormDrawer open={editing != null} onOpenChange={(o) => !o && setEditing(null)} titleNew={c.baseAddMfr}
        titleEdit={c.baseRename} isEdit={editing !== "new"} fields={fields} value={form} onChange={setForm}
        onSubmit={() => saveMfr.mutate()} submitting={saveMfr.isPending} />
      {aliasOf && <AliasDrawer c={c} mfr={aliasOf} onClose={() => setAliasOf(null)}
        onAdd={(alias) => toAlias.mutate({ code: aliasOf.mfrCode, alias })} adding={toAlias.isPending} />}
    </div>
  );
}

function AliasDrawer({ c, mfr, onClose, onAdd, adding }: {
  c: ElecCopy; mfr: ElecMfrRow; onClose: () => void; onAdd: (alias: string) => void; adding: boolean;
}) {
  const [alias, setAlias] = useState("");
  const list = useQuery({ queryKey: ["elec-alias", mfr.mfrCode], queryFn: () => api.listElecAliases(mfr.mfrCode) });
  const cols: Column<ElecAliasRow>[] = [
    { header: c.baseColRaw, cell: (a) => <span className="font-mono">{a.aliasNorm}</span> },
    { header: "", cell: (a) => (a.source === "SEED" ? c.baseAliasSeed : c.baseAliasOps) },
    { header: c.colCreated, cell: (a) => when(a.createdAt) },
  ];
  return (
    <Drawer open onOpenChange={(o) => !o && onClose()} title={fill(c.baseAliasesOf, { code: mfr.mfrCode })} width="w-[640px]">
      <div className="mb-4 flex gap-2">
        <Input className="flex-1" value={alias} placeholder={c.baseAliasPh} onChange={(e) => setAlias(e.target.value)} />
        <Button disabled={!alias.trim() || adding} onClick={() => { onAdd(alias.trim()); setAlias(""); }}>{c.baseAddAlias}</Button>
      </div>
      <DataTable columns={cols} rows={list.data} loading={list.isLoading} error={list.error}
        onRetry={() => list.refetch()} empty={c.baseAliasEmpty} rowKey={(a) => a.aliasNorm} />
    </Drawer>
  );
}

/** 字段取值与 elec-svc `Columns.Field` 一一对应；文案逐个写死，不拼动态键（拼了 i18n 闸门就看不见这一片） */
function fieldOptions(c: ElecCopy) {
  return [
    { value: "MPN", label: c.fieldMPN }, { value: "MFR", label: c.fieldMFR }, { value: "QTY", label: c.fieldQTY },
    { value: "DC", label: c.fieldDC }, { value: "PACKAGE", label: c.fieldPACKAGE }, { value: "PRICE", label: c.fieldPRICE },
    { value: "MOQ", label: c.fieldMOQ }, { value: "SPQ", label: c.fieldSPQ }, { value: "PACKING", label: c.fieldPACKING },
    { value: "CONDITION", label: c.fieldCONDITION }, { value: "CURRENCY", label: c.fieldCURRENCY },
    { value: "LEAD", label: c.fieldLEAD }, { value: "REGION", label: c.fieldREGION },
  ];
}

/**
 * 库存表的表头写法（库存上传二期 AC23）。认列第二级就是这张表：写法在这里，就不用走大模型、也不用供应商手工选列。
 * 全局的当场改、当场生效；「各家学到的」是供应商自己选过列的写法，几家都这么写就值得提升。
 */
function HeaderAliases({ c }: { c: ElecCopy }) {
  const qc = useQueryClient();
  const [scope, setScope] = useState<ElecHeaderAliasScope>("GLOBAL");
  const [kwInput, setKwInput] = useState("");
  const [kw, setKw] = useState("");
  const [alias, setAlias] = useState("");
  const [field, setField] = useState("");
  const options = fieldOptions(c);
  const labelOf = (f: string) => options.find((o) => o.value === f)?.label ?? f;

  const list = useQuery({
    queryKey: ["elec-header-alias", scope, kw],
    queryFn: () => api.listElecHeaderAliases({ scope, keyword: kw || undefined }),
  });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["elec-header-alias"] });

  const create = useMutation({
    mutationFn: (v: { alias: string; field: string; promote?: boolean }) =>
      api.createElecHeaderAlias({ alias: v.alias, field: v.field }),
    onSuccess: (_, v) => {
      notify.success(v.promote ? c.hdrPromoted : c.hdrSaved);
      if (!v.promote) { setAlias(""); setField(""); }
      refresh();
    },
    onError: (e) => notify.error((e as Error).message),
  });
  const update = useMutation({
    mutationFn: (v: { id: number; field?: string; status?: string }) =>
      api.updateElecHeaderAlias(v.id, { field: v.field, status: v.status }),
    onSuccess: () => { notify.success(c.hdrSaved); refresh(); },
    onError: (e) => notify.error((e as Error).message),
  });

  const raw: Column<ElecHeaderAliasRow> = {
    header: c.baseColRaw,
    cell: (a) => <div className="font-medium">{a.aliasRaw}<div className="font-mono txt-caption text-muted-foreground">{a.aliasNorm}</div></div>,
  };
  const globalCols: Column<ElecHeaderAliasRow>[] = [
    raw,
    {
      header: c.hdrColField,
      cell: (a) => (
        <FilterSelect value={a.field} options={options} aria-label={c.hdrColField} disabled={update.isPending}
          onChange={(v) => v && v !== a.field && a.id != null && update.mutate({ id: a.id, field: v })} />
      ),
    },
    { header: c.hdrColSource, cell: (a) => (a.source === "SEED" ? c.baseAliasSeed : c.baseAliasOps) },
    {
      header: c.colStatus,
      cell: (a) => (a.status === "ACTIVE" ? c.hdrActive : <span className="text-muted-foreground">{c.hdrDisabled}</span>),
    },
    { header: c.colCreated, cell: (a) => when(a.updatedAt) },
    {
      header: "",
      cell: (a) => a.id == null ? null : (
        <Button size="sm" variant="ghost" disabled={update.isPending}
          onClick={() => update.mutate({ id: a.id!, status: a.status === "ACTIVE" ? "DISABLED" : "ACTIVE" })}>
          {a.status === "ACTIVE" ? c.hdrDisable : c.hdrEnable}
        </Button>
      ),
    },
  ];
  const learnedCols: Column<ElecHeaderAliasRow>[] = [
    raw,
    { header: c.hdrColField, cell: (a) => labelOf(a.field) },
    { header: c.hdrColUsers, cell: (a) => qty(a.supplierCount), numeric: true },
    { header: c.hdrColLast, cell: (a) => when(a.updatedAt) },
    {
      header: "",
      cell: (a) => (
        <Button size="sm" variant="ghost" disabled={create.isPending}
          onClick={() => create.mutate({ alias: a.aliasRaw, field: a.field, promote: true })}>{c.hdrPromote}</Button>
      ),
    },
  ];

  return (
    <section className="space-y-3">
      <SectionHeader title={c.hdr} />
      <Notice tone="info">{c.hdrHint}</Notice>
      <div className="flex flex-wrap items-center gap-2">
        <Input className="w-56" value={alias} placeholder={c.hdrAddPh} onChange={(e) => setAlias(e.target.value)} />
        <FilterSelect value={field} onChange={setField} options={options} allLabel={c.hdrPickField} aria-label={c.hdrPickField} />
        <Button disabled={alias.trim().length < 2 || !field || create.isPending}
          onClick={() => create.mutate({ alias: alias.trim(), field })}>{c.hdrAdd}</Button>
      </div>
      <Tabs tabs={[{ key: "GLOBAL", label: c.hdrGlobal }, { key: "LEARNED", label: c.hdrLearned }]}
        value={scope} onChange={(k) => setScope(k as ElecHeaderAliasScope)} />
      <Toolbar>
        <Input className="w-56" value={kwInput} placeholder={c.hdrSearchPh}
          onChange={(e) => setKwInput(e.target.value)} onKeyDown={(e) => e.key === "Enter" && setKw(kwInput.trim())} />
      </Toolbar>
      <DataTable columns={scope === "GLOBAL" ? globalCols : learnedCols} rows={list.data} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        empty={scope === "GLOBAL" ? c.hdrEmptyGlobal : c.hdrEmptyLearned}
        rowKey={(a) => `${a.source}:${a.aliasNorm}:${a.field}`} />
    </section>
  );
}
