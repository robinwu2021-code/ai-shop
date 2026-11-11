"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { SaveStoreIndustry, StoreIndustry } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, FieldGrid } from "@/components/ui/drawer";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { HelpNote } from "@/components/ui/help-note";
import { Notice } from "@/components/ui/notice";
import type { SystemCopy } from "./copy";

type Form = SaveStoreIndustry & { child: boolean };

export function StoreIndustryTab({ c, canWrite }: { c: SystemCopy; canWrite: boolean }) {
  const qc = useQueryClient();
  const rows = useQuery({ queryKey: ["store-industries"], queryFn: () => api.listStoreIndustries() });
  const types = useQuery({ queryKey: ["mall-business-types"], queryFn: () => api.mallBusinessTypes() });
  const [form, setForm] = useState<Form | null>(null);
  const parents = (rows.data ?? []).filter((r) => !r.subIndustryCode);
  const save = useMutation({
    mutationFn: ({ child: _child, ...command }: Form) => api.saveStoreIndustry(command),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["store-industries"] }); setForm(null); notify.success(c.siSaved); },
  });
  const create = (child: boolean) => setForm({ child, industryCode: "", subIndustryCode: "", businessTypeCode: "",
    nameZh: "", nameTw: "", nameEn: "", sort: 0, enabled: false, expectedVersion: null, reason: "" });
  const edit = (r: StoreIndustry) => {
    const { version, ...fields } = r;
    setForm({ ...fields, child: !!r.subIndustryCode, expectedVersion: version, reason: "" });
  };
  const columns: Column<StoreIndustry>[] = [
    { header: c.siIndustry, cell: (r) => r.industryCode },
    { header: c.siSub, cell: (r) => r.subIndustryCode || c.siParent },
    { header: c.siZh, cell: (r) => r.nameZh },
    { header: c.siTw, cell: (r) => r.nameTw },
    { header: c.siType, cell: (r) => r.businessTypeCode },
    { header: c.siSort, cell: (r) => r.sort, numeric: true },
    { header: c.siEnabled, cell: (r) => <div className="flex gap-1"><Badge tone={r.enabled ? "success" : "muted"}>{r.enabled ? c.siEnabled : c.siDisabled}</Badge>
      {!!r.subIndustryCode && parents.some((p) => p.industryCode === r.industryCode && !p.enabled) && <Badge tone="warning">{c.siParentOff}</Badge>}</div> },
    { header: c.siVersion, cell: (r) => r.version, numeric: true },
    { header: c.siEdit, cell: (r) => canWrite && <Button size="sm" variant="outline" onClick={() => edit(r)}>{c.siEdit}</Button> },
  ];
  const valid = form && /^[A-Za-z0-9_.:-]{1,32}$/.test(form.industryCode)
    && (!form.child || /^[A-Za-z0-9_.:-]{1,48}$/.test(form.subIndustryCode))
    && form.nameZh.trim() && form.nameTw.trim() && form.reason.trim() && Number.isInteger(form.sort) && form.sort >= 0
    && types.data?.some((t) => t.draft?.typeCode === form.businessTypeCode);

  return <div className="space-y-4">
    <HelpNote>{c.siHelp}</HelpNote>
    {canWrite && <div className="flex gap-2">
      <Button variant="outline" onClick={() => create(false)}>{c.siNewIndustry}</Button>
      <Button variant="outline" disabled={!parents.length} onClick={() => create(true)}>{c.siNewSub}</Button>
    </div>}
    {types.error && <Notice tone="warning">{c.siTypesError}<Button variant="ghost" onClick={() => types.refetch()}>{c.siRetry}</Button></Notice>}
    {types.data?.length === 0 && <Notice tone="info">{c.siNoTypes}</Notice>}
    <DataTable columns={columns} rows={rows.data} loading={rows.isLoading} error={rows.error}
      onRetry={() => rows.refetch()} rowKey={(r) => `${r.industryCode}:${r.subIndustryCode}`} empty={c.siEmpty} />
    <Drawer open={!!form} onOpenChange={(open) => !open && setForm(null)} title={c.siEdit}>
      {form && <div className="space-y-4">
        <FieldGrid>
          <div><Label htmlFor="si-industry">{c.siIndustry}</Label>
            {form.child ? <Select id="si-industry" value={form.industryCode} disabled={form.expectedVersion !== null}
              onChange={(e) => { const value = e.target.value; setForm((f) => f && { ...f, industryCode: value }); }}>
              <option value="">{c.siChoose}</option>{parents.map((p) => <option key={p.industryCode} value={p.industryCode}>{p.nameZh} · {p.industryCode}</option>)}
            </Select> : <Input id="si-industry" value={form.industryCode} maxLength={32} disabled={form.expectedVersion !== null}
              onChange={(e) => { const value = e.target.value; setForm((f) => f && { ...f, industryCode: value }); }} />}</div>
          {form.child && <div><Label htmlFor="si-sub">{c.siSub}</Label><Input id="si-sub" value={form.subIndustryCode} maxLength={48}
            disabled={form.expectedVersion !== null} onChange={(e) => { const value = e.target.value; setForm((f) => f && { ...f, subIndustryCode: value }); }} /></div>}
          <div><Label htmlFor="si-type">{c.siType}</Label><Select id="si-type" value={form.businessTypeCode}
            onChange={(e) => { const value = e.target.value; setForm((f) => f && { ...f, businessTypeCode: value }); }}>
            <option value="">{c.siChoose}</option>{(types.data ?? []).map((t) => <option key={t.draft?.typeCode} value={t.draft?.typeCode}>
              {t.draft?.name} · {t.draft?.typeCode}{t.catalogVersion ? "" : ` · ${c.siUnpublished}`}</option>)}
          </Select></div>
          <div><Label htmlFor="si-zh">{c.siZh}</Label><Input id="si-zh" value={form.nameZh} maxLength={32} onChange={(e) => { const value = e.target.value; setForm((f) => f && { ...f, nameZh: value }); }} /></div>
          <div><Label htmlFor="si-tw">{c.siTw}</Label><Input id="si-tw" value={form.nameTw} maxLength={32} onChange={(e) => { const value = e.target.value; setForm((f) => f && { ...f, nameTw: value }); }} /></div>
          <div><Label htmlFor="si-en">{c.siEn}</Label><Input id="si-en" value={form.nameEn ?? ""} maxLength={64} onChange={(e) => { const value = e.target.value; setForm((f) => f && { ...f, nameEn: value }); }} /></div>
          <div><Label htmlFor="si-sort">{c.siSort}</Label><Input id="si-sort" type="number" min={0} value={form.sort} onChange={(e) => { const value = Number(e.target.value); setForm((f) => f && { ...f, sort: value }); }} /></div>
        </FieldGrid>
        <Label htmlFor="si-enabled">{c.siEnabled}</Label><Switch id="si-enabled" checked={form.enabled} onChange={(enabled) => setForm((f) => f && { ...f, enabled })} />
        <Label htmlFor="si-reason">{c.siReason}</Label><Input id="si-reason" value={form.reason} maxLength={1024} onChange={(e) => { const value = e.target.value; setForm((f) => f && { ...f, reason: value }); }} />
        <Button disabled={!canWrite || !valid} loading={save.isPending} onClick={() => save.mutate(form)}>{c.siSave}</Button>
      </div>}
    </Drawer>
  </div>;
}
