"use client";

// 快递与轨迹（矩阵 P-5.2.1 / 5.2.2；TDD-物流模块 O1–O4）。
//
// 运营在这里能做的：换运单号、订阅失败时重放、看各物流渠道能不能用。轨迹本身来自承运商，平台不编。
// 页面上没有「手工加一条轨迹」——那样做出来的轨迹是平台自己写的故事，
// 一旦与承运商的记录不一致，纠纷时反而站不住。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import type { BindState, Carrier, LogisticsCapability, Shipment, ShipmentProfile, ShipmentStatus, SubscribeState } from "@/lib/types";
import { usePaging } from "@/lib/use-paging";
import { type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { HelpNote } from "@/components/ui/help-note";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input, Select } from "@/components/ui/input";
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

const useSubStateMap = (c: FulfillmentCopy): StatusMap<SubscribeState> => ({
  PENDING: { label: c.subPending, tone: "muted" },
  DONE: { label: c.subDone, tone: "success" },
  // 判死要人处理：换渠道重放，或去看渠道总览
  FATAL: { label: c.subFatal, tone: "danger" },
  ENDED: { label: c.subEnded, tone: "muted" },
  NA: { label: c.subNa, tone: "muted" },
});

const useBindStateMap = (c: FulfillmentCopy): StatusMap<BindState> => ({
  NA: { label: c.bindNa, tone: "muted" },
  WAITING: { label: c.bindWaiting, tone: "info" },
  DONE: { label: c.bindDone, tone: "success" },
  FATAL: { label: c.bindFatal, tone: "danger" },
});

const useProfileMap = (c: FulfillmentCopy): StatusMap<ShipmentProfile> => ({
  WX: { label: c.profileWx, tone: "muted" },
  SELF: { label: c.profileSelf, tone: "muted" },
});

const capLabel = (c: FulfillmentCopy, cap: LogisticsCapability["capability"]) =>
  ({ SUBSCRIBE: c.capSubscribe, PUSH: c.capPush, PROBE: c.capProbe, BIND: c.capBind })[cap];

const useCarrierMap = (c: FulfillmentCopy): StatusMap<Carrier> => ({
  SF: { label: c.carrierSf, tone: "muted" },
  JD: { label: c.carrierJd, tone: "muted" },
  YTO: { label: c.carrierYto, tone: "muted" },
});

export function ExpressTab({ c, canEdit, canReplay }: { c: FulfillmentCopy; canEdit: boolean; canReplay: boolean }) {
  const qc = useQueryClient();
  const statusMap = useShipStatusMap(c);
  const carrierMap = useCarrierMap(c);
  const subStateMap = useSubStateMap(c);
  const bindStateMap = useBindStateMap(c);
  const profileMap = useProfileMap(c);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [carrier, setCarrier] = useState("");
  const [subState, setSubState] = useState("");
  const [profile, setProfile] = useState("");
  const { page, setPage, size, setSize } = usePaging();
  const [current, setCurrent] = useState<Shipment | null>(null);
  const [form, setForm] = useState({ waybillNo: "", reason: "" });
  const [replayChannel, setReplayChannel] = useState("");
  const [channelsOpen, setChannelsOpen] = useState(false);

  const q = { keyword, status, carrier, subState, profile, page, size };
  const list = useQuery({ queryKey: ["shipments", q], queryFn: () => api.listShipments(q) });
  // 渠道总览只在抽屉打开时拉；重放下拉也用它（只列订阅可用的渠道）
  const channels = useQuery({
    queryKey: ["logistics-channels"],
    queryFn: () => api.listLogisticsChannels(),
    enabled: channelsOpen || !!current,
  });
  const subscribable = (channels.data ?? []).filter((ch) =>
    ch.capabilities.some((cap) => cap.capability === "SUBSCRIBE" && cap.available));

  const replay = useMutation({
    mutationFn: (action: "SUBSCRIBE" | "WX_BIND") => api.replayShipment({
      shipmentNo: current!.shipmentNo, action, channel: action === "SUBSCRIBE" && replayChannel ? replayChannel : undefined,
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["shipments"] });
      setCurrent(null);
      notify.success(c.toastReplayAccepted);
    },
  });
  const terminal = (s: Shipment) => s.status === "DELIVERED" || s.status === "CANCELLED";

  const save = useMutation({
    mutationFn: () => api.updateWaybill({ shipmentNo: current!.shipmentNo, ...form }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["shipments"] });
      setCurrent(null);
      notify.success(c.toastWaybillSaved);
    },
  });

  const open = (s: Shipment) => { setCurrent(s); setForm({ waybillNo: s.waybillNo, reason: "" }); setReplayChannel(""); };

  const columns: Column<Shipment>[] = [
    { header: c.colShipmentNo, cell: (s) => <IdCell value={s.shipmentNo} />, numeric: true, align: "start" },
    { header: c.colOrderNo, cell: (s) => <IdCell value={s.orderNo} />, numeric: true, align: "start" },
    { header: c.colCarrier, cell: (s) => <StatusBadge map={carrierMap} value={s.carrier} /> },
    { header: c.colWaybill, cell: (s) => s.waybillNo, numeric: true, align: "start" },
    { header: c.colShipStatus, cell: (s) => <StatusBadge map={statusMap} value={s.status} /> },
    {
      header: c.colSubscribe,
      cell: (s) => (s.subState ? <StatusBadge map={subStateMap} value={s.subState} /> : "—"),
    },
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
        <FilterSelect aria-label={c.filterSubState} value={subState} onChange={(v) => { setSubState(v); setPage(1); }}
          options={subStateMap} allLabel={c.filterSubStateAll} />
        <FilterSelect aria-label={c.filterProfile} value={profile} onChange={(v) => { setProfile(v); setPage(1); }}
          options={profileMap} allLabel={c.filterProfileAll} />
        <Button size="sm" variant="secondary" className="ms-auto" onClick={() => setChannelsOpen(true)}>
          {c.btnChannels}
        </Button>
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
          current && canEdit && !terminal(current) ? (
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
                {current.receiverPhoneLast4 ? (
                  <Field className="mb-3" label={c.fieldPhoneLast4}>{current.receiverPhoneLast4}</Field>
                ) : null}
                {current.profile ? (
                  <Field className="mb-3" label={c.fieldProfile}>{profileMap[current.profile].label}</Field>
                ) : null}
              </FieldGrid>
            </DrawerSection>

            {/*
              跟踪状态：运营处理「买家看不到物流」时要回答哪一环断了 ——
              订阅订上没有、走的哪家、最后一次失败说了什么；微信 token 换上没有。失败原文只给运营看。
            */}
            {current.subState && (
              <DrawerSection title={c.secTracking}>
                <FieldGrid>
                  <Field className="mb-3" label={c.fieldSubscribe}>
                    <span className="flex flex-wrap items-center gap-2">
                      <StatusBadge map={subStateMap} value={current.subState} />
                      {current.subChannel ? <span className="txt-caption">{current.subChannel}</span> : null}
                      {current.subAttempts ? (
                        <span className="txt-caption text-muted-foreground">
                          {fill(c.fieldSubAttempts, { n: current.subAttempts })}
                        </span>
                      ) : null}
                    </span>
                  </Field>
                  {current.bindState ? (
                    <Field className="mb-3" label={c.fieldBind}>
                      <StatusBadge map={bindStateMap} value={current.bindState} />
                    </Field>
                  ) : null}
                </FieldGrid>
                {current.subError ? (
                  <p className="mb-2 txt-caption text-destructive">{c.fieldSubError}：{current.subError}</p>
                ) : null}
                {current.bindError ? (
                  <p className="mb-2 txt-caption text-destructive">{c.fieldBindError}：{current.bindError}</p>
                ) : null}
                {current.atLocker ? <p className="mb-2 txt-caption">{c.fieldAtLocker}</p> : null}
                {canReplay && !terminal(current) && (
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <Select aria-label={c.replayChannel} value={replayChannel}
                      onChange={(e) => setReplayChannel(e.target.value)}>
                      <option value="">{c.replayChannelAuto}</option>
                      {subscribable.map((ch) => <option key={ch.name} value={ch.name}>{ch.name}</option>)}
                    </Select>
                    <Button size="sm" variant="outline" loading={replay.isPending && replay.variables === "SUBSCRIBE"}
                      onClick={() => replay.mutate("SUBSCRIBE")}>{c.btnReplaySubscribe}</Button>
                    {current.profile === "WX" && (
                      <Button size="sm" variant="outline" loading={replay.isPending && replay.variables === "WX_BIND"}
                        onClick={() => replay.mutate("WX_BIND")}>{c.btnReplayBind}</Button>
                    )}
                  </div>
                )}
              </DrawerSection>
            )}

            <DrawerSection title={c.secWaybill}>
              <div className="mb-3 space-y-1">
                <Label htmlFor="sh-waybill" required>{c.colWaybill}</Label>
                <Input id="sh-waybill" className="w-full" value={form.waybillNo}
                  disabled={!canEdit || terminal(current)}
                  onChange={(e) => setForm((p) => ({ ...p, waybillNo: e.target.value }))} />
                <p className="txt-caption text-muted-foreground">
                  {terminal(current) ? c.waybillLockedHint : c.waybillHint}
                </p>
              </div>
              {!terminal(current) && (
                <Field className="mb-0" label={c.fieldWaybillReason}>
                  <Textarea value={form.reason} disabled={!canEdit}
                    onChange={(v) => setForm((p) => ({ ...p, reason: v }))}
                    placeholder={c.waybillReasonPlaceholder} rows={2} />
                </Field>
              )}
            </DrawerSection>

            <DrawerSection title={c.secTraces}>
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

      {/* 渠道总览（O4）：哪家渠道哪种能力能不能用、为什么不能用 —— 订阅判死时先来这里看 */}
      <Drawer open={channelsOpen} onOpenChange={setChannelsOpen} title={c.channelsTitle} desc={c.channelsDesc} width="w-[520px]">
        <div className="space-y-4">
          {(channels.data ?? []).map((ch) => (
            <div key={ch.name} className="rounded-card border border-border p-3">
              <div className="mb-2 flex items-center gap-2">
                <span className="txt-strong">{ch.name}</span>
                {!ch.enabled && <span className="txt-caption text-muted-foreground">{c.channelOff}</span>}
              </div>
              <ul className="space-y-1">
                {ch.capabilities.map((cap) => (
                  <li key={cap.capability} className="flex flex-wrap items-center gap-2 txt-caption">
                    <Badge tone={cap.available ? "success" : "danger"}>{capLabel(c, cap.capability)}</Badge>
                    {!cap.available && cap.reason ? <span className="text-destructive">{cap.reason}</span> : null}
                  </li>
                ))}
              </ul>
              <p className="mt-2 txt-caption text-muted-foreground">
                {c.channelCarriers}：{ch.carriers.join(" / ") || "—"}
                {ch.routes.length ? ` · ${c.channelRoutes}：${ch.routes.join(" / ")}` : ""}
              </p>
            </div>
          ))}
        </div>
      </Drawer>
    </>
  );
}
