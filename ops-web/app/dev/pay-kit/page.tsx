"use client";

// 支付中心组件示例（dev-only，不进菜单，只能 URL 直达）。TDD-支付中心运营页面 P0。
// 演示一页「表格 + 行操作」的全部形态，数据来自内存（lib/pay-ops/demo-orders.ts）：
//   查看（只读详情抽屉，底栏操作在同一抽屉里替换）· 编辑备注 / 结算账户（单一任务抽屉 + 本地草稿，
//   结算账户含敏感字段与审批提示）· 补查（确认）· 关闭（填理由弹框）· 新建（页面级抽屉 + 草稿）。
import * as React from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { FilterSelect } from "@/components/ui/filter-select";
import { TabHeader } from "@/components/ui/tab-header";
import { StatusBadge } from "@/components/ui/status-badge";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { MaskedValue } from "@/components/ui/masked-value";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import type { FieldDef } from "@/components/ui/form-drawer";
import { ApprovalSubmitNotice, PayListPage, PayRefCell, useDraftOperator, usePayOrderStatusMap } from "@/components/payment/pay-kit";
import { createDemoOrder, getDemoOrder, listDemoOrders, updateDemoOrder, type DemoOrder } from "@/lib/pay-ops/demo-orders";
import { money, fmtTime } from "@/lib/utils";

const PAGE = "dev-pay-kit";

const REMARK_FIELDS: FieldDef[] = [
  { key: "remark", label: "运营备注", type: "textarea", required: true, maxLength: 200, help: "只给运营看，不同步给商户" },
];
const SETTLE_FIELDS: FieldDef[] = [
  { key: "settleBank", label: "开户行", required: true },
  { key: "settleAccount", label: "结算账号", required: true, sensitive: true, help: "敏感字段：不会存进本地草稿" },
];
const CREATE_FIELDS: FieldDef[] = [
  { key: "merchantName", label: "商户名称", required: true },
  { key: "amount", label: "金额（元）", type: "number", required: true, min: 1 },
  { key: "channel", label: "渠道", type: "select", required: true, options: [
    { value: "", label: "请选择" }, { value: "NEWEBPAY", label: "蓝新" }, { value: "LINEPAY", label: "LINE Pay" }, { value: "MOCKPAY", label: "测试渠道" },
  ] },
  { key: "remark", label: "备注", type: "textarea", maxLength: 200 },
];

export default function PayKitDemoPage() {
  const qc = useQueryClient();
  const operator = useDraftOperator();
  const statusMap = usePayOrderStatusMap();
  const [page, setPage] = React.useState(1);
  const [size, setSize] = React.useState(10);
  const [status, setStatus] = React.useState("");
  const [keyword, setKeyword] = React.useState("");
  const query = useQuery({
    queryKey: ["dev-pay-kit", page, size, status, keyword],
    queryFn: () => listDemoOrders({ page, size, status, keyword }),
    placeholderData: keepPreviousData,
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ["dev-pay-kit"] });

  const actions: ActionSpec<DemoOrder>[] = React.useMemo(() => [
    {
      key: "view", label: "查看", kind: "drawer", title: (r) => `支付订单 ${r?.payOrderNo}`, width: "w-[520px]",
      render: (ctx) => {
        const r = ctx.row!;
        return (
          <DetailBody
            sections={[
              { title: "订单", fields: [
                { label: "订单号", value: <PayRefCell value={r.payOrderNo} /> },
                { label: "状态", value: <StatusBadge value={r.status} map={statusMap} /> },
                { label: "商户", value: r.merchantName },
                { label: "金额", value: money(r.amount, r.currency) },
                { label: "渠道", value: r.channel },
                { label: "创建时间", value: fmtTime(r.createdAt) },
              ] },
              { title: "运营信息", fields: [
                { label: "备注", value: r.remark || "—", full: true },
                { label: "开户行", value: r.settleBank || "—" },
                { label: "结算账号", value: <MaskedValue value={r.settleAccount} canReveal /> },
              ] },
            ]}
            actions={<DetailActions ctx={ctx} keys={["edit-remark", "settle-account", "dismiss"]} />}
          />
        );
      },
    },
    {
      key: "edit-remark", label: "编辑备注", kind: "drawer", title: () => "编辑备注",
      render: (ctx) => (
        <>
          {ctx.back && <BackLink onClick={ctx.back} />}
          <ActionForm
            fields={REMARK_FIELDS}
            initial={{ remark: ctx.row!.remark }}
            draft={operator ? { operator, page: PAGE, action: "edit-remark", id: ctx.row!.payOrderNo } : null}
            baseVersion={ctx.row!.version}
            isEdit
            onSubmit={async (v) => { await updateDemoOrder(ctx.row!.payOrderNo, { remark: String(v.remark) }, ctx.row!.version); await refresh(); }}
            onDone={ctx.back ?? ctx.close}
          />
        </>
      ),
    },
    {
      key: "settle-account", label: "结算账户", kind: "drawer", title: () => "修改结算账户",
      render: (ctx) => (
        <>
          {ctx.back && <BackLink onClick={ctx.back} />}
          <ApprovalSubmitNotice />
          <ActionForm
            fields={SETTLE_FIELDS}
            initial={{ settleBank: ctx.row!.settleBank, settleAccount: "" }}
            draft={operator ? { operator, page: PAGE, action: "settle-account", id: ctx.row!.payOrderNo } : null}
            baseVersion={ctx.row!.version}
            isEdit
            successMessage="已提交审批（示例：直接生效）"
            onSubmit={async (v) => { await updateDemoOrder(ctx.row!.payOrderNo, { settleBank: String(v.settleBank), settleAccount: String(v.settleAccount) }, ctx.row!.version); await refresh(); }}
            onDone={ctx.close}
          />
        </>
      ),
    },
    {
      key: "recover", label: "补查", kind: "confirm", visible: (r) => r?.status === "PAYING",
      confirm: (r) => ({ title: `向渠道补查 ${r?.payOrderNo}？`, desc: "按渠道最新结果更新订单状态" }),
      run: async (r) => { await updateDemoOrder(r!.payOrderNo, { status: "PAID" }); await refresh(); },
    },
    {
      key: "dismiss", label: "关闭订单", kind: "dialog", danger: true, visible: (r) => r?.status === "CREATED" || r?.status === "PAYING",
      confirm: (r) => ({ title: `关闭 ${r?.payOrderNo}`, desc: "关闭后买家不能再支付这笔订单", danger: true }),
      run: async (r, reason) => { await updateDemoOrder(r!.payOrderNo, { status: "CLOSED", remark: `关闭：${reason}` }); await refresh(); },
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
  ], [operator, statusMap]);

  const pageActions: ActionSpec<DemoOrder>[] = React.useMemo(() => [{
    key: "create", label: "新建订单", kind: "drawer", title: () => "新建订单（示例）",
    render: (ctx) => (
      <ActionForm
        fields={CREATE_FIELDS}
        initial={{ merchantName: "", amount: "", channel: "", remark: "" }}
        draft={operator ? { operator, page: PAGE, action: "create" } : null}
        onSubmit={async (v) => { await createDemoOrder({ merchantName: String(v.merchantName), amount: Math.round(Number(v.amount) * 100), channel: String(v.channel), remark: String(v.remark ?? "") }); await refresh(); }}
        onDone={ctx.close}
      />
    ),
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }], [operator]);

  const columns: Column<DemoOrder>[] = [
    { header: "订单号", cell: (r) => <PayRefCell value={r.payOrderNo} /> },
    { header: "商户", cell: (r) => r.merchantName },
    { header: "金额", numeric: true, cell: (r) => money(r.amount, r.currency) },
    { header: "渠道", cell: (r) => r.channel },
    { header: "状态", cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: "创建时间", cell: (r) => fmtTime(r.createdAt) },
  ];

  return (
    <div className="p-6">
      <PayListPage<DemoOrder>
        header={<TabHeader tabs={[{ key: "demo", label: "支付中心组件示例" }]} value="demo" onChange={() => {}} desc="dev-only · 内存数据" />}
        help="一页 = 筛选 + 表格 + 行操作。抽屉一次只有一个，弹框可叠在抽屉上；抽屉里的编辑自动存本地草稿，刷新、关页后可从「我的草稿」继续。"
        search={keyword}
        onSearch={(v) => { setKeyword(v); setPage(1); }}
        searchPlaceholder="订单号 / 商户"
        filters={<FilterSelect value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={statusMap} allLabel="全部状态" />}
        query={query}
        page={page}
        size={size}
        onPage={setPage}
        onSize={(n) => { setSize(n); setPage(1); }}
        columns={columns}
        rowKey={(r) => r.payOrderNo}
        actions={actions}
        pageActions={pageActions}
        resolve={(id) => getDemoOrder(id)}
        draftPage={PAGE}
      />
    </div>
  );
}

function BackLink({ onClick }: { onClick: () => void }) {
  return <button type="button" className="mb-3 txt-caption text-primary-ink hover:underline focus-ring rounded-field" onClick={onClick}>← 返回详情</button>;
}
