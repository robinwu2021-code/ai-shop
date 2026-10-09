"use client";

// 快递与轨迹（矩阵 P-5.2.1 / 5.2.2）。
//
// 运营在这里能做的只有**一件事**：换运单号。轨迹本身来自承运商，平台不编。
// 页面上没有「手工加一条轨迹」——那样做出来的轨迹是平台自己写的故事，
// 一旦与承运商的记录不一致，纠纷时反而站不住。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import type { Carrier, Shipment, ShipmentStatus } from "@/lib/types";
import { usePaging } from "@/lib/use-paging";
import { type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { HelpNote } from "@/components/ui/help-note";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { PagedTable } from "@/components/ui/paged-table";
import { IdCell } from "@/components/ui/misc";
import { traceStepIndex } from "@/lib/trace-step";
import type { FulfillmentCopy } from "./copy";

const useShipStatusMap = (c: FulfillmentCopy): StatusMap<ShipmentStatus> => ({
  CREATED: { label: c.shipCreated, tone: "muted" },
  PICKED_UP: { label: c.shipPickedUp, tone: "info" },
  IN_TRANSIT: { label: c.shipInTransit, tone: "info" },
  DELIVERING: { label: c.shipDelivering, tone: "info" },
  DELIVERED: { label: c.shipDelivered, tone: "success" },
  // 疑难件不是终态：承运商还可能派送成功，所以是警告不是失败
  EXCEPTION: { label: c.shipException, tone: "warning" },
  // 发货撤回 / 换了单号：这张运单不再跟踪
  CANCELLED: { label: c.shipCancelled, tone: "muted" },
});

const useCarrierMap = (c: FulfillmentCopy): StatusMap<Carrier> => ({
  SF: { label: c.carrierSf, tone: "muted" },
  JD: { label: c.carrierJd, tone: "muted" },
  YTO: { label: c.carrierYto, tone: "muted" },
});

export function ExpressTab({ c, canEdit }: { c: FulfillmentCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const statusMap = useShipStatusMap(c);
  const carrierMap = useCarrierMap(c);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [carrier, setCarrier] = useState("");
  const { page, setPage, size, setSize } = usePaging();
  const [current, setCurrent] = useState<Shipment | null>(null);
  const [form, setForm] = useState({ waybillNo: "", reason: "" });

  const q = { keyword, status, carrier, page, size };
  const list = useQuery({ queryKey: ["shipments", q], queryFn: () => api.listShipments(q) });

  const save = useMutation({
    mutationFn: () => api.updateWaybill({ shipmentNo: current!.shipmentNo, ...form }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["shipments"] });
      setCurrent(null);
      notify.success(c.toastWaybillSaved);
    },
  });

  const open = (s: Shipment) => { setCurrent(s); setForm({ waybillNo: s.waybillNo, reason: "" }); };

  const columns: Column<Shipment>[] = [
    { header: c.colShipmentNo, cell: (s) => <IdCell value={s.shipmentNo} />, numeric: true, align: "start" },
    { header: c.colOrderNo, cell: (s) => <IdCell value={s.orderNo} />, numeric: true, align: "start" },
    { header: c.colCarrier, cell: (s) => <StatusBadge map={carrierMap} value={s.carrier} /> },
    { header: c.colWaybill, cell: (s) => s.waybillNo, numeric: true, align: "start" },
    { header: c.colShipStatus, cell: (s) => <StatusBadge map={statusMap} value={s.status} /> },
    { header: c.colReceiver, cell: (s) => s.receiver },
    { header: c.colRegion, cell: (s) => s.region },
    { header: c.colUpdatedAt, cell: (s) => fmtTime(s.updatedAt) },
    {
      header: c.colActions,
      cell: (s) => (
        <Button size="sm" variant="outline" onClick={() => open(s)}>{c.actionTrace}</Button>
      ),
    },
  ];

  return (
    <>
      <HelpNote className="mb-3">{c.expressNotice}</HelpNote>
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchShipment}>
        <FilterSelect aria-label={c.filterCarrier} value={carrier} onChange={(v) => { setCarrier(v); setPage(1); }}
          options={carrierMap} allLabel={c.filterCarrierAll} />
        <FilterSelect aria-label={c.filterShipStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }}
          options={statusMap} allLabel={c.filterShipStatusAll} />
      </Toolbar>
      <PagedTable
        query={list}
        page={page}
        size={size}
        onPage={setPage}
        onSize={setSize}
        loading={list.isLoading}
        columns={columns}
        rowKey={(s) => s.shipmentNo}
        empty={c.emptyShipment}
      />

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current ? fill(c.traceTitle, { no: current.shipmentNo }) : ""}
        desc={current ? statusMap[current.status].label : undefined}
        width="w-[520px]"
        footer={
          current && canEdit && current.status !== "DELIVERED" ? (
            <Button loading={save.isPending} onClick={() => save.mutate()}>{c.btnSaveWaybill}</Button>
          ) : null
        }
      >
        {current && (
          <div>
            <DrawerSection first title={c.secShipOverview}>
              <FieldGrid>
                <Field className="mb-3" label={c.colOrderNo}>{current.orderNo}</Field>
                <Field className="mb-3" label={c.colCarrier}>{carrierMap[current.carrier].label}</Field>
                <Field className="mb-3" label={c.colReceiver}>{current.receiver}</Field>
                <Field className="mb-3" label={c.colRegion}>{current.region}</Field>
              </FieldGrid>
            </DrawerSection>

            <DrawerSection title={c.secWaybill}>
              <div className="mb-3 space-y-1">
                <Label htmlFor="sh-waybill" required>{c.colWaybill}</Label>
                <Input id="sh-waybill" className="w-full" value={form.waybillNo}
                  disabled={!canEdit || current.status === "DELIVERED"}
                  onChange={(e) => setForm((p) => ({ ...p, waybillNo: e.target.value }))} />
                <p className="txt-caption text-muted-foreground">
                  {current.status === "DELIVERED" ? c.waybillLockedHint : c.waybillHint}
                </p>
              </div>
              {current.status !== "DELIVERED" && (
                <Field className="mb-0" label={c.fieldWaybillReason}>
                  <Textarea value={form.reason} disabled={!canEdit}
                    onChange={(v) => setForm((p) => ({ ...p, reason: v }))}
                    placeholder={c.waybillReasonPlaceholder} rows={2} />
                </Field>
              )}
            </DrawerSection>

            <DrawerSection title={c.secTraces}>
              {/*
                先说这一单走的哪个渠道。运营处理的是异常件，而「买家看不到物流」的第一个问题
                就是「哪条链、哪一环断了」——渠道名与失败原因都只给运营看，不给买家看。
              */}
              <div className="mb-3 flex flex-wrap items-center gap-2">
                <span className="txt-caption text-muted-foreground">{c.traceChannel}</span>
                <span className="txt-caption">{current.displayChannel ?? "self-map"}</span>
                {current.displayFailReason ? (
                  <span className="txt-caption text-destructive">
                    {c.traceFail}：{current.displayFailReason}
                  </span>
                ) : null}
              </div>
              {/*
                四档步骤条：一眼看出走到第几步，不用读完整条轨迹。

                判据必须与端上的 packages/ui/src/components/sh-trace.vue 保持一致，
                否则同一单运营看到「运输中」而买家看到「派送中」—— 运营是照着自己这屏
                答买家的问的。两处各写一份是因为 ops-web 不引 packages/shared；
                改一处就要改另一处（异常件不画步骤条、派送中靠节点文案认）。
              */}
              {current.status !== "EXCEPTION" && (
              <ol className="mb-4 flex gap-2">
                {([
                  [c.traceStepPicked, 0],
                  [c.traceStepTransit, 1],
                  [c.traceStepDelivering, 2],
                  [c.traceStepSigned, 3],
                ] as const).map(([label, i]) => {
                    const at = traceStepIndex(current);
                    return (
                      <li key={label} className="flex flex-1 flex-col items-center gap-1">
                        {/* 分段横条而不是小圆点：抽屉比手机宽得多，一条占满那一档更好认 */}
                        <span
                          className={`h-1 w-full rounded-chip ${
                            i < at ? "bg-success" : i === at ? "bg-primary" : "bg-muted"
                          }`}
                        />
                        <span className={`txt-caption ${i <= at ? "" : "text-muted-foreground"}`}>
                          {label}
                        </span>
                      </li>
                    );
                })}
              </ol>
              )}
              {/* 轨迹倒序：最新的节点是运营要先看到的那条 */}
              <ol className="space-y-3">
                {current.traces.map((t, i) => (
                  <li key={`${t.at}-${i}`} className="border-l-2 border-border pl-3">
                    <p className="txt-body">{t.text}</p>
                    <p className="txt-caption text-muted-foreground">
                      {fmtTime(t.at)}{t.location ? ` · ${t.location}` : ""}
                    </p>
                  </li>
                ))}
              </ol>
            </DrawerSection>
          </div>
        )}
      </Drawer>
    </>
  );
}
