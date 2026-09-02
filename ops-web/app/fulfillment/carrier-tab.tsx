"use client";

// 第三方运力配置（矩阵 P-5.2.4）。
//
// 这一页配错的后果不是"显示不对"，而是**订单发不出去**。所以每一行都把
// 「为什么这家现在停不掉 / 启不了」直接写在按钮旁边，而不是等点了才报错：
// 没配密钥的启不了、有在途单的停不掉、最后一家启用的也停不掉。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import type { CarrierConfig } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { HelpNote } from "@/components/ui/help-note";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { FulfillmentCopy } from "./copy";

interface Form {
  carrier: string;
  name: string;
  priority: string;
  pickupCutoff: string;
  slaHours: string;
}

export function CarrierTab({ c, canEdit }: { c: FulfillmentCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const [editing, setEditing] = useState<Form | null>(null);

  const list = useQuery({ queryKey: ["carriers"], queryFn: () => api.listCarriers() });
  // 「还有几个在途单」是停用能不能点的依据，所以这一页要读快递单
  const shipments = useQuery({ queryKey: ["shipments", "all"], queryFn: () => api.listShipments({ size: 100 }) });

  const inFlightOf = (carrier: string) =>
    (shipments.data?.records ?? []).filter((s) => s.carrier === carrier && s.status !== "DELIVERED").length;
  const enabledCount = (list.data ?? []).filter((x) => x.enabled).length;

  const save = useMutation({
    mutationFn: () =>
      api.saveCarrier({
        carrier: editing!.carrier as CarrierConfig["carrier"],
        name: editing!.name,
        priority: Number(editing!.priority),
        pickupCutoff: editing!.pickupCutoff,
        slaHours: Number(editing!.slaHours),
      }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["carriers"] }); setEditing(null); notify.success(c.toastCarrierSaved); },
  });

  const toggle = useMutation({
    mutationFn: (v: { carrier: string; enabled: boolean }) => api.setCarrierEnabled(v.carrier, v.enabled),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["carriers"] });
      notify.success(r.enabled ? c.toastCarrierOn : c.toastCarrierOff);
    },
  });

  /** 这家现在能不能启停，以及为什么不能 —— 界面与 mock 层同向。 */
  const gate = (x: CarrierConfig): { can: boolean; why?: string } => {
    if (!x.enabled) {
      return x.apiKeyConfigured ? { can: true } : { can: false, why: c.gateNoKey };
    }
    const n = inFlightOf(x.carrier);
    if (n > 0) return { can: false, why: fill(c.gateInFlight, { n }) };
    if (enabledCount <= 1) return { can: false, why: c.gateLastOne };
    return { can: true };
  };

  const columns: Column<CarrierConfig>[] = [
    { header: c.colPriority, cell: (x) => x.priority, numeric: true },
    { header: c.colCarrierName, cell: (x) => x.name },
    { header: c.colAccount, cell: (x) => x.accountMasked },
    {
      header: c.colApiKey,
      // 密钥只给"配没配"，不给值 —— 契约里根本没有密钥字段
      cell: (x) => (x.apiKeyConfigured ? <Badge tone="success">{c.keyReady}</Badge> : <Badge tone="danger">{c.keyMissing}</Badge>),
    },
    { header: c.colCutoff, cell: (x) => x.pickupCutoff, numeric: true },
    { header: c.colSla, cell: (x) => fill(c.slaHoursValue, { n: x.slaHours }), numeric: true },
    {
      header: c.colInFlight,
      cell: (x) => {
        const n = inFlightOf(x.carrier);
        return n > 0 ? <Badge tone="warning">{fill(c.inFlightCount, { n })}</Badge> : <span className="text-muted-foreground">{fill(c.inFlightCount, { n: 0 })}</span>;
      },
      numeric: true,
    },
    {
      header: c.colCarrierStatus,
      cell: (x) => (x.enabled ? <Badge tone="success">{c.carrierOn}</Badge> : <span className="text-muted-foreground">{c.carrierOff}</span>),
    },
    { header: c.colCarrierUpdatedAt, cell: (x) => `${fmtTime(x.updatedAt)} · ${x.updatedBy}` },
    {
      header: c.colActions,
      cell: (x) => {
        const g = gate(x);
        return (
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" disabled={!canEdit}
              onClick={() => setEditing({
                carrier: x.carrier, name: x.name, priority: String(x.priority),
                pickupCutoff: x.pickupCutoff, slaHours: String(x.slaHours),
              })}>
              {c.actionEditCarrier}
            </Button>
            <Button size="sm" variant="ghost" disabled={!canEdit || !g.can}
              onClick={() => toggle.mutate({ carrier: x.carrier, enabled: !x.enabled })}>
              {x.enabled ? c.actionCarrierOff : c.actionCarrierOn}
            </Button>
            {/* 不能点的原因直接写在旁边，而不是等点了才报错 */}
            {g.why && <span className="txt-caption text-muted-foreground">{g.why}</span>}
          </div>
        );
      },
    },
  ];

  return (
    <>
      <HelpNote className="mb-3">{c.carrierNotice}</HelpNote>
      <DataTable
        columns={columns} rows={list.data} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(x) => x.carrier}
        empty={c.emptyCarrier}
      />

      <Drawer
        open={!!editing}
        onOpenChange={(o) => !o && setEditing(null)}
        title={editing ? fill(c.editCarrierTitle, { name: editing.name }) : ""}
        width="w-[480px]"
        footer={canEdit ? <Button loading={save.isPending} onClick={() => save.mutate()}>{c.btnSaveCarrier}</Button> : null}
      >
        {editing && (
          <div>
            <DrawerSection first title={c.secCarrierBasic}>
              <div className="mb-3 space-y-1">
                <Label htmlFor="cr-name" required>{c.colCarrierName}</Label>
                <Input id="cr-name" className="w-full" value={editing.name}
                  onChange={(e) => setEditing((p) => p && { ...p, name: e.target.value })} />
              </div>
              <FieldGrid>
                <div className="mb-3 space-y-1">
                  <Label htmlFor="cr-priority" required>{c.colPriority}</Label>
                  <Input id="cr-priority" className="w-full" value={editing.priority}
                    onChange={(e) => setEditing((p) => p && { ...p, priority: e.target.value })} />
                </div>
                <div className="mb-3 space-y-1">
                  <Label htmlFor="cr-sla" required>{c.colSla}</Label>
                  <Input id="cr-sla" className="w-full" value={editing.slaHours}
                    onChange={(e) => setEditing((p) => p && { ...p, slaHours: e.target.value })} />
                </div>
              </FieldGrid>
              <div className="space-y-1">
                <Label htmlFor="cr-cutoff" required>{c.colCutoff}</Label>
                <Input id="cr-cutoff" className="w-full" value={editing.pickupCutoff}
                  onChange={(e) => setEditing((p) => p && { ...p, pickupCutoff: e.target.value })} />
                <p className="txt-caption text-muted-foreground">{c.cutoffHint}</p>
              </div>
              <p className="mt-3 txt-caption text-muted-foreground">{c.priorityHint}</p>
            </DrawerSection>

            <DrawerSection title={c.secApiKey}>
              <Field className="mb-0" label={c.colApiKey}>
                {list.data?.find((x) => x.carrier === editing.carrier)?.apiKeyConfigured ? c.keyReady : c.keyMissing}
              </Field>
              <p className="mt-1 txt-caption text-muted-foreground">{c.apiKeyHint}</p>
            </DrawerSection>
          </div>
        )}
      </Drawer>
    </>
  );
}
