"use client";

// 支付渠道（OP03）。行操作：查看（基本 + 端点）· 编辑（抽屉）· 启用 / 停用（理由弹框）· 诊断（理由弹框，结果 toast）。
// 页面级：新增渠道（抽屉）。端点按「沙箱 / 生产」两个地址字段编辑，回调方式共用一个 —— near-pay 每个环境最多一条端点。
import * as React from "react";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import type { FieldDef } from "@/components/ui/form-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import type { ChannelSave } from "@/app/payment-center/_api/api";
import type { ChannelEndpoint, ChannelRow } from "@/app/payment-center/_api/types";
import { useQuery } from "@tanstack/react-query";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { useCac } from "./channel-accounts";
import { fmtTime } from "@/lib/utils";
import { DetailLoader, MiniTable, reasonField, useRun, useStatusMap, useTabList, usePc, yesNo, type C } from "./shared";

const NAME = "channels";
const URL_RE = { re: "^$|^https://.{1,247}$", msg: "https://" };

/** 适配器下拉取 near-pay `/adapters`；取不到（无权、接口失败）时退回手填，不挡新建 / 编辑 */
function fields(c: C, adapters: string[] = []): FieldDef[] {
  return [
    { key: "channelCode", label: c.channelCode, required: true, readOnlyOnEdit: true, pattern: { re: "^[A-Z0-9_]{2,32}$", msg: "A-Z 0-9 _" } },
    { key: "name", label: c.channelName, required: true, maxLength: 128 },
    { key: "channelType", label: c.channelType, type: "select", required: true, readOnlyOnEdit: true, options: [{ value: "FIAT", label: "FIAT" }, { value: "CRYPTO", label: "CRYPTO" }] },
    adapters.length
      ? { key: "adapterCode", label: c.adapterCode, type: "select", required: true, options: [{ value: "", label: "—" }, ...adapters.map((a) => ({ value: a, label: a }))] }
      : { key: "adapterCode", label: c.adapterCode, required: true, maxLength: 32 },
    { key: "market", label: c.market, maxLength: 8 },
    { key: "sortNo", label: c.sortNo, type: "number", min: 0 },
    { key: "testChannel", label: c.testChannel, type: "switch" },
    { key: "sandboxUrl", label: c.sandboxUrl, help: c.urlHelp, pattern: URL_RE, maxLength: 255 },
    { key: "productionUrl", label: c.productionUrl, help: c.urlHelp, pattern: URL_RE, maxLength: 255 },
    { key: "callbackStyle", label: c.callbackStyle, type: "select", options: [{ value: "", label: "—" }, { value: "PATH", label: "PATH" }, { value: "QUERY", label: "QUERY" }] },
    reasonField(c),
  ];
}

function toForm(r: ChannelRow | null) {
  const url = (env: string) => r?.endpoints.find((e) => e.environment === env)?.baseUrl ?? "";
  return {
    channelCode: r?.channelCode ?? "", name: r?.name ?? "", channelType: r?.channelType ?? "FIAT", adapterCode: r?.adapterCode ?? "",
    market: r?.market ?? "", sortNo: r?.sortNo ?? 0, testChannel: r?.testChannel ?? false,
    sandboxUrl: url("SANDBOX"), productionUrl: url("PRODUCTION"), callbackStyle: r?.endpoints[0]?.callbackStyle ?? "", reason: "",
  };
}

function toSave(v: Record<string, unknown>, expectedVersion: number, keep: ChannelRow | null): ChannelSave {
  const style = (v.callbackStyle as string) || null;
  const endpoints = (["SANDBOX", "PRODUCTION"] as const)
    .map((env) => ({ environment: env, baseUrl: String(env === "SANDBOX" ? v.sandboxUrl : v.productionUrl).trim(), callbackStyle: style }))
    .filter((e) => e.baseUrl);
  return {
    expectedVersion, channelCode: String(v.channelCode).trim(), name: String(v.name).trim(), channelType: String(v.channelType), adapterCode: String(v.adapterCode).trim(),
    platformCredentialKeyId: keep?.platformCredentialKeyId ?? null, sortNo: Number(v.sortNo ?? 0), market: (v.market as string) || null, testChannel: !!v.testChannel,
    endpoints, reason: String(v.reason),
  };
}

export function ChannelsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const ca = useCac();
  const enabledMap = useStatusMap("enabled", ["ENABLED", "DISABLED"], { ENABLED: "success" });
  const statusMap: StatusMap<string> = React.useMemo(() => ({ ...enabledMap, PENDING: { label: ca.chPending, tone: "warning" as const } }), [enabledMap, ca]);
  const list = useTabList<ChannelRow, { status: string }>(NAME, { status: "" }, (api, q) => api.channels.list(q));
  const { api } = list;
  const adapters = useQuery({ queryKey: ["pay-center", NAME, "adapters", api.source], queryFn: () => api.channels.adapters(), retry: false, staleTime: 10 * 60_000 });
  const adapterCodes = (adapters.data ?? []).map((a) => a.adapterCode);
  const draft = (action: string, id?: string) => (operator ? { operator, page: "pay-channels", action, id } : null);

  const actions: ActionSpec<ChannelRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[560px]", title: () => c.channelDetailTitle,
      render: (ctx) => (
        <DetailLoader name={NAME} id={ctx.row!.channelCode} load={() => api.channels.detail(ctx.row!.channelCode)}>
          {(d) => {
            const r = d.record;
            return (
              <DetailBody
                sections={[
                  { title: c.sectionBasic, fields: [
                    { label: c.channelCode, value: r.channelCode },
                    { label: c.colStatus, value: <StatusBadge value={r.status} map={statusMap} /> },
                    { label: c.channelName, value: r.name },
                    { label: c.channelType, value: r.channelType },
                    { label: c.adapterCode, value: r.adapterCode },
                    { label: c.market, value: r.market },
                    { label: c.enabledProducts, value: r.enabledProductCount },
                    { label: c.testChannel, value: yesNo(r.testChannel, c) },
                    { label: c.credentialKeyId, value: r.platformCredentialKeyId },
                    { label: c.colUpdated, value: `${fmtTime(r.updatedAt)} · ${r.updatedBy ?? ""}` },
                  ] },
                  { title: c.sectionEndpoints, content: <MiniTable<ChannelEndpoint> rows={r.endpoints} rowKey={(e) => e.environment} empty={c.emptyEndpoints} columns={[
                    { header: c.environment, cell: (e) => e.environment },
                    { header: c.baseUrl, cell: (e) => <span className="break-all">{e.baseUrl}</span> },
                    { header: c.callbackStyle, cell: (e) => e.callbackStyle ?? "—" },
                  ]} /> },
                ]}
                actions={<DetailActions ctx={ctx} keys={["disable", "diagnose", "enable", "pending", "edit"]} />}
              />
            );
          }}
        </DetailLoader>
      ),
    },
    {
      key: "edit", label: c.edit, kind: "drawer", perm: "pay:channel:manage", title: () => c.editChannelTitle,
      render: (ctx) => (
        <ActionForm
          notify={false}
          fields={fields(c, adapterCodes)}
          initial={toForm(ctx.row)}
          isEdit
          draft={draft("edit", ctx.row!.channelCode)}
          baseVersion={ctx.row!.version}
          onSubmit={(v) => run(() => api.channels.update(ctx.row!.channelCode, toSave(v, ctx.row!.version, ctx.row)))}
          onDone={ctx.back ?? ctx.close}
        />
      ),
    },
    {
      key: "enable", label: c.enable, kind: "dialog", perm: "pay:channel:manage", visible: (r) => !!r && r.status !== "ENABLED",
      confirm: (r) => ({ title: `${c.enable} ${r?.name}`, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.channels.enable(r!.channelCode, { expectedVersion: r!.version, reason })),
    },
    {
      key: "disable", label: c.disable, kind: "dialog", perm: "pay:channel:manage", danger: true, visible: (r) => !!r && r.status !== "DISABLED",
      confirm: (r) => ({ title: `${c.disable} ${r?.name}`, danger: true, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.channels.disable(r!.channelCode, { expectedVersion: r!.version, reason })),
    },
    {
      key: "pending", label: ca.markPending, kind: "dialog", perm: "pay:channel:manage", visible: (r) => !!r && r.status !== "PENDING",
      confirm: (r) => ({ title: fill(ca.markPendingTitle, { name: r?.name ?? "" }), desc: ca.markPendingDesc, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.channels.pending(r!.channelCode, { expectedVersion: r!.version, reason })),
    },
    {
      key: "diagnose", label: c.diagnose, kind: "dialog", perm: "pay:channel:manage",
      confirm: () => ({ title: c.diagnoseTitle, reasonHint: c.reasonHint }),
      run: (r, reason) => run(async () => {
        const job = await api.channels.diagnose(r!.channelCode, { expectedVersion: r!.version, reason });
        notify.info(c.diagnoseDone.replace("{job}", job.jobNo).replace("{status}", job.status));
        return job;
      }),
    },
  ];

  const pageActions: ActionSpec<ChannelRow>[] = [{
    key: "create", label: c.create, kind: "drawer", perm: "pay:channel:manage", title: () => c.createChannelTitle,
    render: (ctx) => (
      <ActionForm
          notify={false}
        fields={fields(c, adapterCodes)}
        initial={toForm(null)}
        draft={draft("create")}
        onSubmit={(v) => run(() => api.channels.create(toSave(v, 0, null)))}
        onDone={ctx.close}
      />
    ),
  }];

  const columns: Column<ChannelRow>[] = [
    { header: c.channelCode, cell: (r) => <span className="font-mono txt-caption">{r.channelCode}</span> },
    { header: c.channelName, cell: (r) => r.name },
    { header: c.adapterCode, cell: (r) => r.adapterCode },
    { header: c.enabledProducts, numeric: true, cell: (r) => r.enabledProductCount },
    { header: c.testChannel, cell: (r) => yesNo(r.testChannel, c) },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.colUpdated, cell: (r) => fmtTime(r.updatedAt) },
  ];

  return (
    <PayListPage<ChannelRow>
      header={header}
      help={c.channelsDesc}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.channelCode}
      actions={actions}
      empty={c.emptyChannels}
      pageActions={pageActions}
      resolve={(id) => api.channels.detail(id).then((d) => d.record)}
      draftPage="pay-channels"
    />
  );
}
