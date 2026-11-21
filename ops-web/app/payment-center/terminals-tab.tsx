"use client";

// 支付终端（OP08）。行操作：查看（基本 + 就绪检查）· 编辑（抽屉）· 核验（抽屉：凭证编号；核验人 ≠ 配置人）· 启用 / 停用（理由弹框）
// · 生成激活码（抽屉，一次性明文）· 挂失（强确认，无请求体）· 退役（强确认 + 理由）。页面级：新增（抽屉）。
import * as React from "react";
import { useCopy } from "@/lib/use-copy";
import { TerminalCredRetire } from "./p9-panels";
import { P9_COPY } from "./p9-copy";
import { P11_COPY } from "./p11-copy";
import { PosConfigSection, SoftPosSection, isNewebpayTerminal, isPaybyTerminal } from "./p11-terminal";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { FieldDef } from "@/components/ui/form-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import type { TerminalSave } from "@/app/payment-center/_api/api";
import { CONNECTION_MODES, DEVICE_STATUSES, TERMINAL_CONFIG_STATUSES, type TerminalRow } from "@/app/payment-center/_api/types";
import { money } from "@/lib/utils";
import { csv, fromMinor, joinCsv, options, reasonField, toMinor, useRun, useStatusMap, useTabList, usePc, yesNo, type C } from "./shared";
import { SecretOnce } from "./secret-once";

const NAME = "terminals";

function fields(c: C, connLabel: (v: string) => string): FieldDef[] {
  return [
    { key: "name", label: c.terminalName, required: true, maxLength: 64 },
    { key: "merchantNo", label: c.merchantNo, required: true, readOnlyOnEdit: true, maxLength: 64 },
    { key: "storeNo", label: c.storeNo, required: true, readOnlyOnEdit: true, maxLength: 64 },
    { key: "payChannel", label: c.payChannelField, required: true, maxLength: 32 },
    { key: "payMerchantNo", label: c.payMerchantNo, maxLength: 64 },
    { key: "serialNo", label: c.serialNo, required: true, readOnlyOnEdit: true, maxLength: 64 },
    { key: "model", label: c.model, maxLength: 64 },
    { key: "connectionMode", label: c.connectionModeLabel, type: "select", required: true, options: options(CONNECTION_MODES, connLabel) },
    { key: "productCodes", label: c.productCodes, required: true, help: c.productCodesHelp },
    { key: "dailyRefundLimit", label: c.dailyRefundLimit, type: "number", required: true, min: 0 },
    { key: "receiptName", label: c.receiptName, maxLength: 64 },
    reasonField(c),
  ];
}

function toForm(r: TerminalRow | null) {
  return {
    name: r?.name ?? "", merchantNo: r?.merchantNo ?? "", storeNo: r?.storeNo ?? "", payChannel: r?.payChannel ?? "", payMerchantNo: r?.payMerchantNo ?? "",
    serialNo: r?.serialNo ?? "", model: r?.model ?? "", connectionMode: r?.connectionMode ?? "STANDALONE", productCodes: joinCsv(r?.productCodes),
    dailyRefundLimit: r ? fromMinor(r.dailyRefundLimitMinor) : 0, receiptName: r?.receiptName ?? "", reason: "",
  };
}

/** 编辑时不在表单里的字段（渠道终端号、备注）原样回传 */
function toSave(v: Record<string, unknown>, expectedVersion: number, keep: TerminalRow | null): TerminalSave {
  const s = (k: string) => String(v[k] ?? "").trim();
  return {
    expectedVersion, reason: s("reason"), name: s("name"), merchantNo: s("merchantNo"), storeNo: s("storeNo"), payChannel: s("payChannel"), payMerchantNo: s("payMerchantNo"),
    channelTerminalNo: keep?.channelTerminalNo ?? "", serialNo: s("serialNo"), model: s("model"), connectionMode: s("connectionMode"), productCodes: csv(v.productCodes),
    receiptName: s("receiptName"), remark: keep?.remark ?? "", dailyRefundLimitMinor: toMinor(v.dailyRefundLimit),
  };
}

export function TerminalsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const m9 = useCopy(P9_COPY);
  const m11 = useCopy(P11_COPY);
  const cfgMap = useStatusMap("tcfg", TERMINAL_CONFIG_STATUSES, { ENABLED: "success" });
  const verifyMap = useStatusMap("tverify", ["UNVERIFIED", "VERIFIED"], { VERIFIED: "success", UNVERIFIED: "warning" });
  const deviceMap = useStatusMap("device", DEVICE_STATUSES, { ACTIVE: "success", LOST: "danger", RETIRED: "muted" });
  const connMap = useStatusMap("conn", CONNECTION_MODES);
  const list = useTabList<TerminalRow, { configurationStatus: string; deviceStatus: string }>(NAME, { configurationStatus: "", deviceStatus: "" }, (api, q) => api.terminals.list(q));
  const { api } = list;
  const draft = (action: string, id?: string) => (operator ? { operator, page: "pay-terminals", action, id } : null);
  const alive = (r: TerminalRow | null) => !!r && r.deviceStatus !== "RETIRED";

  const actions: ActionSpec<TerminalRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[860px]", title: () => c.terminalDetailTitle,
      render: (ctx) => {
        const r = ctx.row!;
        return (
          <DetailBody
            sections={[
              { title: c.sectionBasic, fields: [
                { label: c.terminalNo, value: <PayRefCell value={r.terminalNo} /> },
                { label: c.terminalName, value: r.name },
                { label: c.configStatusLabel, value: <StatusBadge value={r.configurationStatus} map={cfgMap} /> },
                { label: c.verificationStatusLabel, value: <StatusBadge value={r.verificationStatus} map={verifyMap} /> },
                { label: c.deviceStatusLabel, value: <StatusBadge value={r.deviceStatus} map={deviceMap} /> },
                { label: c.connectionModeLabel, value: connMap[r.connectionMode]?.label ?? r.connectionMode },
                { label: c.merchantNo, value: r.merchantNo },
                { label: c.storeNo, value: r.storeNo },
                { label: c.colChannel, value: r.payChannel },
                { label: c.payMerchantNo, value: r.payMerchantNo },
                { label: c.serialNo, value: r.serialNo },
                { label: c.model, value: r.model },
                { label: c.productCodes, value: joinCsv(r.productCodes) },
                { label: c.dailyRefundLimit, value: money(r.dailyRefundLimitMinor, r.currency ?? undefined) },
                { label: c.configuredBy, value: r.configuredBy },
                { label: c.verifiedBy, value: r.verifiedBy },
                { label: c.credentialRefLabel, value: r.credentialRef, full: true },
              ] },
              { title: m9.sectionCredRetire, content: <TerminalCredRetire api={api} no={r.terminalNo} /> },
              ...(isPaybyTerminal(r) ? [{ title: m11.posTitle, content: <PosConfigSection api={api} terminal={r} run={run} /> }] : []),
              ...(isNewebpayTerminal(r) ? [{ title: m11.softposTitle, content: <SoftPosSection api={api} terminal={r} run={run} /> }] : []),
              { title: c.readiness, fields: [
                { label: c.canReceive, value: yesNo(r.readiness?.canReceivePayments, c) },
                { label: c.readinessReasons, value: r.readiness?.reasons.join("、") || "—", full: true },
              ] },
            ]}
            actions={<DetailActions ctx={ctx} keys={["retire", "lost", "disable", "activation", "verify", "enable", "edit"]} />}
          />
        );
      },
    },
    {
      key: "edit", label: c.edit, kind: "drawer", perm: "pay:terminal:manage", width: "w-[560px]", title: () => c.editTerminalTitle,
      visible: (r) => alive(r) && r!.configurationStatus !== "ENABLED",
      render: (ctx) => (
        <ActionForm notify={false} fields={fields(c, (v) => connMap[v].label)} initial={toForm(ctx.row)} isEdit draft={draft("edit", ctx.row!.terminalNo)} baseVersion={ctx.row!.version}
          onSubmit={(v) => run(() => api.terminals.update(ctx.row!.terminalNo, toSave(v, ctx.row!.version, ctx.row)))} onDone={ctx.back ?? ctx.close} />
      ),
    },
    {
      key: "verify", label: c.verifyTerminal, kind: "drawer", perm: "pay:terminal:verify", title: () => c.verifyTerminalTitle,
      visible: (r) => alive(r) && r!.verificationStatus === "UNVERIFIED",
      render: (ctx) => (
        <>
          <Notice tone="info" className="mb-4">{c.verifyNote}</Notice>
          <ActionForm notify={false} fields={[{ key: "evidenceRef", label: c.evidenceRef, required: true, maxLength: 128 }, reasonField(c)]} initial={{ evidenceRef: "", reason: "" }}
            draft={draft("verify", ctx.row!.terminalNo)} baseVersion={ctx.row!.version}
            onSubmit={(v) => run(() => api.terminals.verify(ctx.row!.terminalNo, { expectedVersion: ctx.row!.version, evidenceRef: String(v.evidenceRef).trim(), reason: String(v.reason) }))}
            onDone={ctx.back ?? ctx.close} />
        </>
      ),
    },
    { key: "enable", label: c.enable, kind: "dialog", perm: "pay:terminal:manage", visible: (r) => alive(r) && r!.configurationStatus !== "ENABLED" && r!.verificationStatus === "VERIFIED",
      confirm: (r) => ({ title: `${c.enable} ${r?.name}`, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.terminals.enable(r!.terminalNo, { expectedVersion: r!.version, reason })) },
    { key: "disable", label: c.disable, kind: "dialog", perm: "pay:terminal:manage", danger: true, visible: (r) => r?.configurationStatus === "ENABLED",
      confirm: (r) => ({ title: `${c.disable} ${r?.name}`, danger: true, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.terminals.disable(r!.terminalNo, { expectedVersion: r!.version, reason })) },
    {
      key: "activation", label: c.activationCode, kind: "drawer", perm: "pay:terminal:manage", title: () => c.activationCodeTitle, visible: alive,
      render: (ctx) => (
        <SecretOnce note={c.activationNote} button={c.activationGenerate} shownHint={c.activationShown}
          generate={async () => ({ secret: (await api.terminals.activationCode(ctx.row!.terminalNo)).activationCode })} onDone={ctx.back ?? ctx.close} />
      ),
    },
    { key: "lost", label: c.lost, kind: "confirm", perm: "pay:terminal:manage", danger: true, visible: (r) => alive(r) && r!.deviceStatus !== "LOST",
      confirm: (r) => ({ title: c.lostTitle, desc: c.lostDesc, danger: true, requireText: r?.terminalNo }),
      run: (r) => run(() => api.terminals.lost(r!.terminalNo)) },
    { key: "retire", label: c.retire, kind: "dialog", perm: "pay:terminal:manage", danger: true, visible: alive,
      confirm: (r) => ({ title: c.retireTitle, desc: c.retireDesc, danger: true, requireText: r?.terminalNo, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.terminals.retire(r!.terminalNo, { expectedVersion: r!.version, reason })) },
  ];

  const pageActions: ActionSpec<TerminalRow>[] = [{
    key: "create", label: c.create, kind: "drawer", perm: "pay:terminal:manage", width: "w-[560px]", title: () => c.createTerminalTitle,
    render: (ctx) => (
      <ActionForm notify={false} fields={fields(c, (v) => connMap[v].label)} initial={toForm(null)} draft={draft("create")}
        onSubmit={(v) => run(() => api.terminals.create(toSave(v, 0, null)))} onDone={ctx.close} />
    ),
  }];

  const columns: Column<TerminalRow>[] = [
    { header: c.terminalNo, cell: (r) => <PayRefCell value={r.terminalNo} /> },
    { header: c.terminalName, cell: (r) => r.name },
    { header: c.storeNo, cell: (r) => r.storeNo },
    { header: c.colChannel, cell: (r) => r.payChannel },
    { header: c.configStatusLabel, cell: (r) => <StatusBadge value={r.configurationStatus} map={cfgMap} /> },
    { header: c.verificationStatusLabel, cell: (r) => <StatusBadge value={r.verificationStatus} map={verifyMap} /> },
    { header: c.deviceStatusLabel, cell: (r) => <StatusBadge value={r.deviceStatus} map={deviceMap} /> },
  ];

  return (
    <PayListPage<TerminalRow>
      header={header}
      help={c.terminalsDesc}
      filters={<>
        <FilterSelect value={list.filters.configurationStatus} onChange={(v) => list.setFilter("configurationStatus", v)} options={cfgMap} allLabel={c.configStatusLabel} />
        <FilterSelect value={list.filters.deviceStatus} onChange={(v) => list.setFilter("deviceStatus", v)} options={deviceMap} allLabel={c.deviceStatusLabel} />
      </>}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.terminalNo}
      actions={actions}
      pageActions={pageActions}
      resolve={(id) => api.terminals.detail(id).then((d) => d.record)}
      draftPage="pay-terminals"
      empty={c.emptyTerminals}
    />
  );
}
