"use client";

// 门店权益来源：查看来源与可访问时段；零金额独立端赠送 / 撤销。
//
// 只支持**有期限的独立端**零金额赠送；FREE 必需端受保护；不提供「赠送整个 Plan」——
// 那会伪造一笔没人付过的 Plan 价值，后续升级抵扣会把它当真钱算。
// 撤销只针对原始价值全零、未转换/退款/冻结的赠送；付费来源（哪怕已过期）走退款。
import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import type { MallAccessSegment, MallEntitlementSource } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { SectionHeader } from "@/components/ui/section-header";
import { Textarea } from "@/components/ui/textarea";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { Money, StoreBar, fmtAt, newOpNo, showErr, type MallCopy } from "./shared";

const DAY = 86_400_000;

export function SourcesTab({ c, canGrant, canRevoke }: { c: MallCopy; canGrant: boolean; canRevoke: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [storeNo, setStoreNo] = React.useState("");
  const sub = useQuery({ queryKey: ["mall-sub", storeNo], enabled: !!storeNo, queryFn: () => api.mallSubscription(storeNo) });
  const ends = useQuery({ queryKey: ["mall-ends"], queryFn: () => api.mallBusinessEnds() });
  const grantable = (ends.data ?? []).filter((e) => e.draft?.saleEnabled && !e.draft?.bootstrapRequired && e.draft?.enabled);
  const [g, setG] = React.useState({ code: "", days: "30", reason: "" });
  const [opNo, setOpNo] = React.useState(() => newOpNo("GRANT"));

  const refresh = () => qc.invalidateQueries({ queryKey: ["mall-sub", storeNo] });
  const grant = useMutation({
    mutationFn: () => api.grantMallEnd(storeNo, { operationNo: opNo, entitlementCode: g.code, durationDays: Number(g.days), reason: g.reason.trim() }),
    onSuccess: () => { refresh(); setOpNo(newOpNo("GRANT")); setG({ code: "", days: "30", reason: "" }); notify.success(c.toastGranted); },
  });
  const revoke = (s: MallEntitlementSource) => confirm({
    title: c.revokeTitle, desc: fill(c.revokeDesc, { no: s.sourceNo ?? "" }), danger: true, requireReason: true,
    action: (reason) => api.revokeMallSource(storeNo, s.sourceNo!, { operationNo: newOpNo("REVOKE"), reason, expectedVersion: sub.data!.entitlementVersion! })
      .then(() => { refresh(); notify.success(c.toastRevoked); }),
  });

  const revocable = (s: MallEntitlementSource) => s.lifecycle === "GRANT" && s.valuationBasis?.minor === "0";
  const days = (ms?: number) => (ms == null ? "—" : fill(c.daysN, { n: Math.round(ms / DAY) }));
  const sourceColumns: Column<MallEntitlementSource>[] = [
    { header: c.colSourceNo, cell: (s) => <code className="txt-caption">{s.sourceNo}</code> },
    { header: c.fieldEntitlement, cell: (s) => s.entitlementCode },
    { header: c.colLifecycle, cell: (s) => <Badge tone={s.lifecycle === "REVOKED" ? "muted" : s.lifecycle === "PURCHASE" ? "success" : "info"}>{s.lifecycle}</Badge> },
    { header: c.colOrigin, cell: (s) => <code className="txt-caption">{s.originNo}</code> },
    { header: c.colDuration, numeric: true, cell: (s) => s.permanent ? c.permanent : days(s.durationMillis) },
    { header: c.colConsumed, numeric: true, cell: (s) => s.permanent ? "—" : days(s.consumedMillis) },
    { header: c.colValuation, numeric: true, cell: (s) => <Money m={s.valuationBasis} /> },
    { header: c.colActions, cell: (s) => canRevoke && revocable(s) ? <Button size="sm" variant="outline" onClick={() => revoke(s)}>{c.btnRevoke}</Button> : null },
  ];
  const segColumns: Column<MallAccessSegment>[] = [
    { header: c.colSourceNo, cell: (s) => <code className="txt-caption">{s.sourceNo}</code> },
    { header: c.fieldEntitlement, cell: (s) => s.entitlementCode },
    { header: c.colStarts, cell: (s) => fmtAt(s.startsAt) },
    { header: c.colEnds, cell: (s) => fmtAt(s.endsAt) },
  ];
  const valid = g.code && Number(g.days) >= 1 && Number(g.days) <= 3650 && g.reason.trim();

  return (
    <div className="space-y-4">
      <HelpNote>{c.sourcesHelp}</HelpNote>
      <StoreBar c={c} id="src-store" value={storeNo} onSubmit={setStoreNo} />
      {!storeNo ? <p className="txt-caption text-muted-foreground">{c.storeFirst}</p> : (
        <>
          <SectionHeader title={c.secSources} summary={sub.data && fill(c.entitlementVersionN, { v: sub.data.entitlementVersion ?? 0 })} />
          <DataTable columns={sourceColumns} rows={sub.data?.sources} loading={sub.isLoading} error={showErr(sub.error)} onRetry={() => sub.refetch()}
            rowKey={(s) => s.sourceNo ?? ""} empty={c.sourcesEmpty} />
          <SectionHeader title={c.secSegments} />
          <DataTable columns={segColumns} rows={sub.data?.segments} loading={sub.isLoading} error={showErr(sub.error)} onRetry={() => sub.refetch()} rowKey={(s) => `${s.sourceNo}/${s.startsAt}`} empty={c.segmentsEmpty} />
          {canGrant && (
            <div className="space-y-3 rounded-card border border-border p-4">
              <div className="txt-strong">{c.secGrant}</div>
              <p className="txt-caption text-muted-foreground">{c.grantHint}</p>
              <div className="grid gap-3 md:grid-cols-3">
                <div className="space-y-1">
                  <Label htmlFor="gr-end" required>{c.fieldEntitlement}</Label>
                  <Select id="gr-end" className="w-full" value={g.code} onChange={(e) => setG((x) => ({ ...x, code: e.target.value }))}>
                    <option value="">{grantable.length ? c.pickEnd : c.noGrantable}</option>
                    {grantable.map((e) => <option key={e.draft?.code} value={e.draft?.entitlementCode}>{e.draft?.name} ({e.draft?.entitlementCode})</option>)}
                  </Select>
                </div>
                <div className="space-y-1">
                  <Label htmlFor="gr-days" required>{c.fieldDays}</Label>
                  <Input id="gr-days" inputMode="numeric" value={g.days} onChange={(e) => setG((x) => ({ ...x, days: e.target.value }))} />
                </div>
              </div>
              <div className="space-y-1">
                <Label htmlFor="gr-reason" required>{c.fieldReason}</Label>
                <Textarea id="gr-reason" value={g.reason} placeholder={c.reasonPh} onChange={(v) => setG((x) => ({ ...x, reason: v }))} />
              </div>
              <div className="flex justify-end"><Button disabled={!valid} loading={grant.isPending} onClick={() => grant.mutate()}>{c.btnGrant}</Button></div>
            </div>
          )}
        </>
      )}
      {dialog}
    </div>
  );
}
