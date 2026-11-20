"use client";

// 支付产品（OP04）。行操作：查看（方式、接入、限额、退款规则）· 编辑基本信息（抽屉）· 启用 / 停用（理由弹框）。
// 页面级：新增产品（抽屉，一个接入方式 + 一条币种限额起步，账户模式默认平台子商户；多条与账户模式的调整暂在支付端工作区
// —— 再加字段就超过一个抽屉的量，要拆成多个操作，P2 再做）。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, useDraftOperator } from "@/components/payment/pay-kit";
import type { ProductSave } from "@/lib/pay-ops/api";
import { ACCESS_METHODS, PAYMENT_METHODS, REFUND_MODES, TRADE_MODES, type CurrencyLimit, type ProductAccess, type ProductRow } from "@/lib/pay-ops/types";
import { fmtTime, money } from "@/lib/utils";
import { DetailLoader, MiniTable, options, reasonField, toMinor, useRun, useStatusMap, useTabList, usePc, yesNo } from "./shared";

const NAME = "products";
const plain = (v: string) => v;

/** 编辑只动基本信息：其余字段原样回传（near-pay 的 PUT 要整份 ProductSave） */
function saveOf(r: ProductRow, patch: Partial<ProductSave>, reason: string): ProductSave {
  const { status: _s, version, updatedAt: _u, updatedBy: _b, ...rest } = r;
  return { ...rest, ...patch, expectedVersion: version, reason };
}

export function ProductsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const statusMap = useStatusMap("enabled", ["ENABLED", "DISABLED"], { ENABLED: "success" });
  const refundMap = useStatusMap("refund", REFUND_MODES);
  const list = useTabList<ProductRow, { status: string; paymentMethod: string }>(NAME, { status: "", paymentMethod: "" }, (api, q) => api.products.list(q));
  const { api } = list;
  const draft = (action: string, id?: string) => (operator ? { operator, page: "pay-products", action, id } : null);
  const pmOptions = Object.fromEntries(PAYMENT_METHODS.map((m) => [m, { label: m, tone: "muted" as const }]));

  const actions: ActionSpec<ProductRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.productDetailTitle,
      render: (ctx) => (
        <DetailLoader name={NAME} id={ctx.row!.productCode} load={() => api.products.detail(ctx.row!.productCode)}>
          {(d) => {
            const r = d.record;
            return (
              <DetailBody
                sections={[
                  { title: c.sectionBasic, fields: [
                    { label: c.productCode, value: r.productCode },
                    { label: c.colStatus, value: <StatusBadge value={r.status} map={statusMap} /> },
                    { label: c.displayName, value: r.displayName },
                    { label: c.colChannel, value: r.channelCode },
                    { label: c.tradeMode, value: r.tradeMode },
                    { label: c.paymentMethods, value: r.paymentMethods.join(", ") },
                    { label: c.accountModes, value: r.accountModes.join(", ") },
                    { label: c.refundRule, value: refundMap[r.refundRule.refundMode]?.label ?? r.refundRule.refundMode },
                    { label: c.twoPhase, value: yesNo(r.twoPhase, c) },
                    { label: c.protocolProductCode, value: r.protocolProductCode },
                    { label: c.colUpdated, value: `${fmtTime(r.updatedAt)} · ${r.updatedBy ?? ""}` },
                  ] },
                  { title: c.accessMethods, content: <MiniTable<ProductAccess> rows={r.accessMethods} rowKey={(a) => a.accessMethod} empty={c.emptyAccessMethods} columns={[
                    { header: c.accessMethod, cell: (a) => a.accessMethod },
                    { header: c.channelMethod, cell: (a) => a.channelMethod },
                    { header: c.colStatus, cell: (a) => yesNo(a.enabled !== false, c) },
                  ]} /> },
                  { title: c.currencyLimits, content: <MiniTable<CurrencyLimit> rows={r.currencyLimits} rowKey={(l) => l.currency} empty={c.emptyLimits} columns={[
                    { header: c.currency, cell: (l) => l.currency },
                    { header: c.minAmount, numeric: true, cell: (l) => money(l.minMinor, l.currency) },
                    { header: c.maxAmount, numeric: true, cell: (l) => (l.maxMinor ? money(l.maxMinor, l.currency) : "∞") },
                  ]} /> },
                ]}
                actions={<DetailActions ctx={ctx} keys={["disable", "enable", "edit"]} />}
              />
            );
          }}
        </DetailLoader>
      ),
    },
    {
      key: "edit", label: c.edit, kind: "drawer", perm: "pay:product:manage", title: () => c.editProductTitle,
      render: (ctx) => (
        <>
          <Notice tone="info" className="mb-4">{c.editProductNote}</Notice>
          <ActionForm
          notify={false}
            fields={[
              { key: "displayName", label: c.displayName, required: true, maxLength: 128 },
              { key: "sortNo", label: c.sortNo, type: "number", min: 0 },
              { key: "twoPhase", label: c.twoPhase, type: "switch" },
              { key: "protocolProductCode", label: c.protocolProductCode, maxLength: 64 },
              reasonField(c),
            ]}
            initial={{ displayName: ctx.row!.displayName, sortNo: ctx.row!.sortNo, twoPhase: ctx.row!.twoPhase, protocolProductCode: ctx.row!.protocolProductCode ?? "", reason: "" }}
            isEdit
            draft={draft("edit", ctx.row!.productCode)}
            baseVersion={ctx.row!.version}
            onSubmit={(v) => run(() => api.products.update(ctx.row!.productCode, saveOf(ctx.row!, {
              displayName: String(v.displayName), sortNo: Number(v.sortNo ?? 0), twoPhase: !!v.twoPhase, protocolProductCode: (v.protocolProductCode as string) || null,
            }, String(v.reason))))}
            onDone={ctx.back ?? ctx.close}
          />
        </>
      ),
    },
    {
      key: "enable", label: c.enable, kind: "dialog", perm: "pay:product:manage", visible: (r) => r?.status === "DISABLED",
      confirm: (r) => ({ title: `${c.enable} ${r?.displayName}`, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.products.enable(r!.productCode, { expectedVersion: r!.version, reason })),
    },
    {
      key: "disable", label: c.disable, kind: "dialog", perm: "pay:product:manage", danger: true, visible: (r) => r?.status === "ENABLED",
      confirm: (r) => ({ title: `${c.disable} ${r?.displayName}`, danger: true, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.products.disable(r!.productCode, { expectedVersion: r!.version, reason })),
    },
  ];

  const pageActions: ActionSpec<ProductRow>[] = [{
    key: "create", label: c.create, kind: "drawer", perm: "pay:product:manage", width: "w-[560px]", title: () => c.createProductTitle,
    render: (ctx) => (
      <ActionForm
          notify={false}
        fields={[
          { key: "productCode", label: c.productCode, required: true, pattern: { re: "^[A-Z0-9_]{2,64}$", msg: "A-Z 0-9 _" } },
          { key: "channelCode", label: c.channelCode, required: true, maxLength: 32 },
          { key: "displayName", label: c.displayName, required: true, maxLength: 128 },
          { key: "tradeMode", label: c.tradeMode, type: "select", required: true, options: [{ value: "", label: "—" }, ...options(TRADE_MODES, plain)] },
          { key: "paymentMethods", label: c.paymentMethods, type: "multiselect", required: true, options: options(PAYMENT_METHODS, plain) },
          { key: "accessMethod", label: c.accessMethod, type: "select", required: true, options: [{ value: "", label: "—" }, ...options(ACCESS_METHODS, plain)] },
          { key: "channelMethod", label: c.channelMethod, required: true, maxLength: 32 },
          { key: "currency", label: c.currency, required: true, pattern: { re: "^[A-Z]{3}$", msg: "TWD" } },
          { key: "minAmount", label: c.minAmount, type: "number", required: true, min: 0 },
          { key: "maxAmount", label: c.maxAmount, type: "number", required: true, min: 0 },
          { key: "refundMode", label: c.refundMode, type: "select", required: true, options: options(REFUND_MODES, (v) => refundMap[v]?.label ?? v) },
          reasonField(c),
        ]}
        initial={{ productCode: "", channelCode: "", displayName: "", tradeMode: "", paymentMethods: [], accessMethod: "", channelMethod: "", currency: "TWD", minAmount: 1, maxAmount: 0, refundMode: "PARTIAL", reason: "" }}
        draft={draft("create")}
        onSubmit={(v) => run(() => api.products.create({
          expectedVersion: 0, productCode: String(v.productCode).trim(), channelCode: String(v.channelCode).trim(), displayName: String(v.displayName).trim(),
          tradeMode: String(v.tradeMode), paymentMethods: v.paymentMethods as string[], accountModes: ["PLATFORM_SUB"],
          accessMethods: [{ accessMethod: String(v.accessMethod), channelMethod: String(v.channelMethod).trim(), enabled: true }],
          currencyLimits: [{ currency: String(v.currency), amountScale: 2, minMinor: toMinor(v.minAmount), maxMinor: toMinor(v.maxAmount), enabled: true }],
          refundRule: { refundMode: String(v.refundMode) }, twoPhase: false, sortNo: 0, protocolProductCode: null, reason: String(v.reason),
        }))}
        onDone={ctx.close}
      />
    ),
  }];

  const columns: Column<ProductRow>[] = [
    { header: c.productCode, cell: (r) => <span className="font-mono txt-caption">{r.productCode}</span> },
    { header: c.displayName, cell: (r) => r.displayName },
    { header: c.colChannel, cell: (r) => r.channelCode },
    { header: c.paymentMethods, cell: (r) => r.paymentMethods.join(", ") },
    { header: c.accessMethods, cell: (r) => r.accessMethods.map((a) => a.accessMethod).join(", ") },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.colUpdated, cell: (r) => fmtTime(r.updatedAt) },
  ];

  return (
    <PayListPage<ProductRow>
      header={header}
      help={c.productsDesc}
      filters={<>
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />
        <FilterSelect value={list.filters.paymentMethod} onChange={(v) => list.setFilter("paymentMethod", v)} options={pmOptions} allLabel={c.paymentMethods} />
      </>}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.productCode}
      actions={actions}
      empty={c.emptyProducts}
      pageActions={pageActions}
      resolve={(id) => api.products.detail(id).then((d) => d.record)}
      draftPage="pay-products"
    />
  );
}
