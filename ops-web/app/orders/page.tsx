"use client";

// 订单管理（矩阵 P-4.1）。**样板页 ②：列表 + 详情抽屉 + 关联数据 + 导出**。
// 与商家页的差异是刻意的：这里演示「一行展开成一件事」的详情抽屉与跨查询的兄弟单，
// 而不是审核那种状态机推进。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { ReconAxes } from "./recon-axes";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { usePaging } from "@/lib/use-paging";
import { useCan } from "@/lib/use-can";
import { useEditableConfig } from "@/lib/use-editable-config";
import { notify } from "@/lib/notify";
import { fill, useCopy } from "@/lib/use-copy";
import { ORDERS_COPY } from "./copy";
import { MAX_UNPAID_CLOSE_MINUTES, MIN_UNPAID_CLOSE_MINUTES, MINOR_UNIT } from "@/lib/constants";
import { fmtTime, money } from "@/lib/utils";
import { exportCsv } from "@/lib/export-csv";
import type { Order, ReconDiff, ReconDiffType, ReconStatus, RecoverAction } from "@/lib/types";
import { OrderStatusBadge, useFulfillmentTypeMap, useOrderStatusMap, useTrafficSourceMap } from "@/components/status";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Pagination } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { ConfigCard } from "@/components/ui/config-card";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
// 异常单与代客操作各自成块，与其它 tab 只共用文案表 —— 拆出去，页面才不会长到读不动
import { ExceptionTab } from "./exception-tab";
import { ProxyTab } from "./proxy-tab";

type Copy = ReturnType<typeof useCopy<(typeof ORDERS_COPY)["zh"]>>;
const TAB_KEYS = ["search", "exception", "proxy", "pay", "repair", "close"] as const;

/** 差异类型 → 徽标。三类的处置方式不同，颜色也要能一眼分开。 */
const useDiffTypeMap = (c: Copy): StatusMap<ReconDiffType> => ({
  CHANNEL_ONLY: { label: c.diffChannelOnly, tone: "danger" },
  PLATFORM_ONLY: { label: c.diffPlatformOnly, tone: "warning" },
  AMOUNT_DIFF: { label: c.diffAmount, tone: "info" },
});

const useReconStatusMap = (c: Copy): StatusMap<ReconStatus> => ({
  PENDING: { label: c.reconOpen, tone: "warning" },
  RESOLVED: { label: c.reconResolved, tone: "success" },
  IGNORED: { label: c.reconIgnored, tone: "muted" },
});

export default function OrdersPage() {
  return <Suspense fallback={null}><OrdersInner /></Suspense>;
}

function OrdersInner() {
  const c = useCopy(ORDERS_COPY);
  const tabs = useNavTabs("/orders", TAB_KEYS);
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const diffTypeMap = useDiffTypeMap(c);
  const reconStatusMap = useReconStatusMap(c);
  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); setStatus(""); });
  const statusMap = useOrderStatusMap();
  const fulfillMap = useFulfillmentTypeMap();
  const trafficMap = useTrafficSourceMap();

  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [fulfillType, setFulfillType] = useState("");
  const { page, setPage, size, setSize } = usePaging();
  const [current, setCurrent] = useState<Order | null>(null);

  const [diffType, setDiffType] = useState("");
  const [reconStatus, setReconStatus] = useState("");
  const [resolving, setResolving] = useState<ReconDiff | null>(null);
  const [resolveForm, setResolveForm] = useState<{ action: RecoverAction | ""; resolution: string }>({ action: "", resolution: "" });

  const canPay = allow("order:pay:repair");
  const canModify = allow("order:order:modify");
  const canProxy = allow("order:order:proxy");

  const q = { keyword, status, fulfillType, page, size };
  const list = useQuery({ queryKey: ["orders", q], queryFn: () => api.listOrders(q), enabled: tab === "search" });

  // 「掉单补偿」就是对账差异里 CHANNEL_ONLY + 待处置的那个子集 ——
  // 不另建实体：另建就有两份真相，且一定会不同步（与资金域的退款回退同一个道理）。
  const reconQ = {
    keyword, page, size,
    type: tab === "repair" ? "CHANNEL_ONLY" : diffType,
    status: tab === "repair" ? "PENDING" : reconStatus,
  };
  /*
   * 覆盖范围说明。**单独一个查询、不跟着列表走** ——
   * 后端把它做成独立端点的理由就是这个：列表是分页包，
   * 把说明挂在分页包上，翻到第二页时它就没了。
   *
   * ⚠️ 拿不到时**不显示提示条**（而不是显示一句写死的）：
   * 端上写死的话，后端接上渠道账单之后页面还在说「看不见」。
   */
  const coverage = useQuery({
    queryKey: ["recon-coverage"],
    queryFn: () => api.reconCoverage(),
    enabled: tab === "pay" || tab === "repair",
  });

  const recon = useQuery({
    queryKey: ["recon", reconQ],
    queryFn: () => api.listReconDiffs(reconQ),
    enabled: tab === "pay" || tab === "repair",
  });
  const closeRuleQ = useQuery({ queryKey: ["close-rule"], queryFn: () => api.getCloseRule(), enabled: tab === "close" });

  const invalidateRecon = () => qc.invalidateQueries({ queryKey: ["recon"] });
  const resolveMut = useMutation({
    mutationFn: () =>
      api.resolveReconDiff({
        diffNo: resolving!.diffNo,
        action: resolveForm.action || undefined,
        resolution: resolveForm.resolution,
      }),
    onSuccess: () => { invalidateRecon(); setResolving(null); notify.success(c.toastResolved); },
  });
  const ignoreMut = useMutation({
    mutationFn: () => api.ignoreReconDiff({ diffNo: resolving!.diffNo, resolution: resolveForm.resolution }),
    onSuccess: () => { invalidateRecon(); setResolving(null); notify.success(c.toastIgnored); },
  });

  const { form: closeForm, set: setCloseField, reset: resetClose } = useEditableConfig(closeRuleQ.data, (d) => ({
    unpaidMinutes: String(d.unpaidMinutes),
    remindBeforeMinutes: String(d.remindBeforeMinutes),
    autoRefundOnLateCallback: d.autoRefundOnLateCallback,
  }));
  const saveClose = useMutation({
    mutationFn: () =>
      api.saveCloseRule({
        unpaidMinutes: Number(closeForm!.unpaidMinutes),
        remindBeforeMinutes: Number(closeForm!.remindBeforeMinutes),
        autoRefundOnLateCallback: closeForm!.autoRefundOnLateCallback,
      }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["close-rule"] }); resetClose(); notify.success(c.toastCloseSaved); },
  });

  const reconColumns: Column<ReconDiff>[] = [
    { header: c.colDiffNo, cell: (d) => d.diffNo, numeric: true, align: "start" },
    { header: c.colBillDate, cell: (d) => d.billDate },
    { header: c.colChannel, cell: (d) => d.channel },
    { header: c.colDiffType, cell: (d) => <StatusBadge map={diffTypeMap} value={d.type} /> },
    { header: c.colChannelTxn, cell: (d) => d.channelTxnNo ?? c.none, numeric: true, align: "start" },
    { header: c.colSubOrderNo, cell: (d) => d.orderNo ?? c.none, numeric: true, align: "start" },
    { header: c.colChannelAmount, cell: (d) => money(d.channelAmount), numeric: true },
    { header: c.colPlatformAmount, cell: (d) => money(d.platformAmount), numeric: true },
    {
      header: c.colGap,
      numeric: true,
      // 差额是这张表的重点：0 与非 0 是两种性质，不能都渲染成灰数字
      cell: (d) => {
        const gap = d.channelAmount - d.platformAmount;
        return gap === 0 ? <span className="text-muted-foreground">{money(0)}</span> : <Badge tone="danger">{money(gap)}</Badge>;
      },
    },
    { header: c.colReconStatus, cell: (d) => <StatusBadge map={reconStatusMap} value={d.status} /> },
    {
      header: c.colActions,
      cell: (d) =>
        d.status === "PENDING" && canPay ? (
          <Button size="sm" variant="outline" onClick={() => { setResolving(d); setResolveForm({ action: "", resolution: "" }); }}>
            {c.actionResolve}
          </Button>
        ) : <span className="text-muted-foreground">{c.none}</span>,
    },
  ];

  // 兄弟单：同一次结算按商家拆出的其它子订单（E3）。详情打开才查，不预取。
  const siblings = useQuery({
    queryKey: ["orders", "siblings", current?.parentNo],
    queryFn: () => api.listSiblingOrders(current!.parentNo),
    enabled: !!current,
  });

  const columns: Column<Order>[] = [
    { header: c.colSubOrderNo, cell: (o) => o.orderNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (o) => o.merchantName },
    { header: c.colCommunity, cell: (o) => o.communityName },
    { header: c.colFulfill, cell: (o) => <StatusBadge map={fulfillMap} value={o.fulfillType} /> },
    { header: c.colTraffic, cell: (o) => <StatusBadge map={trafficMap} value={o.trafficSource} /> },
    { header: c.colBuyer, cell: (o) => o.buyerNickname },
    { header: c.colPaid, cell: (o) => money(o.payAmount), numeric: true },
    { header: c.colStatus, cell: (o) => <OrderStatusBadge value={o.status} /> },
    { header: c.colCreatedAt, cell: (o) => fmtTime(o.createdAt) },
    {
      header: c.colActions,
      cell: (o) => (
        <Button size="sm" variant="outline" onClick={() => setCurrent(o)}>{c.actionDetail}</Button>
      ),
    },
  ];

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} desc={c.desc} />

      {tab === "exception" && (
        <>
          {!canModify && <ReadOnlyNotice what={c.exceptionReadOnlyWhat} perm="order:order:modify" className="mb-3" />}
          <ExceptionTab c={c} canModify={canModify} />
        </>
      )}

      {tab === "proxy" && (
        <>
          {!canProxy && <ReadOnlyNotice what={c.proxyReadOnlyWhat} perm="order:order:proxy" className="mb-3" />}
          <ProxyTab c={c} canProxy={canProxy} />
        </>
      )}

      {(tab === "pay" || tab === "repair" || tab === "close") && !canPay && (
        <ReadOnlyNotice what={c.payReadOnlyWhat} perm="order:pay:repair" note={c.payReadOnlyNote} className="mb-3" />
      )}

      {tab === "search" && (
      <>
      <Notice className="mb-3">{c.notice}</Notice>

      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); setPage(1); }}
        searchPlaceholder={c.searchPlaceholder}
        onExport={() =>
          exportCsv(
            c.exportSheet,
            [
              { header: c.colSubOrderNo, value: (o: Order) => o.orderNo },
              { header: c.exportParentNo, value: (o: Order) => o.parentNo },
              { header: c.colMerchant, value: (o: Order) => o.merchantName },
              { header: c.colCommunity, value: (o: Order) => o.communityName },
              { header: c.colStatus, value: (o: Order) => statusMap[o.status].label },
              // 导出给财务对账用：给金额本身，不给带货币符号的展示串
              { header: c.exportPaidYuan, value: (o: Order) => (o.payAmount / MINOR_UNIT).toFixed(2) },
              { header: c.colCreatedAt, value: (o: Order) => o.createdAt },
            ],
            list.data?.records ?? [],
          )
        }
      >
        <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={statusMap} allLabel={c.filterStatusAll} />
        <FilterSelect aria-label={c.filterFulfill} value={fulfillType} onChange={(v) => { setFulfillType(v); setPage(1); }} options={fulfillMap} allLabel={c.filterFulfillAll} />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={list.data?.records}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(o) => o.orderNo}
        empty={c.empty}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />
      </>
      )}

      {(tab === "pay" || tab === "repair") && (
        <>
          {/* 四轴总览摆在最上面：先回答「哪一类在看、哪一类没看」，再看具体差异 */}
          {tab === "pay" && <ReconAxes c={c} />}
          <Notice className="mb-3">{tab === "repair" ? c.repairNotice : c.payNotice}</Notice>
          {/*
            ⚠️ **这一条不能省，而且空表时更要显示。**
            后端 ReconService 的类注释写着「页面照它显示提示条，
            否则『今天没有差异』是句假话」—— 而这个接口在此之前
            没有任何调用方，所以那句假话一直挂在这一页上。

            渠道账单接上之后 channelBillConnected 变 true，这条自然消失 ——
            判据来自后端，端上不做第二套。
          */}
          {coverage.data && !coverage.data.channelBillConnected && (
            <Notice className="mb-3" tone="warning">{coverage.data.note}</Notice>
          )}
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchRecon}>
            {tab === "pay" && (
              <>
                <FilterSelect aria-label={c.filterDiffType} value={diffType} onChange={(v) => { setDiffType(v); setPage(1); }} options={diffTypeMap} allLabel={c.filterDiffTypeAll} />
                <FilterSelect aria-label={c.filterReconStatus} value={reconStatus} onChange={(v) => { setReconStatus(v); setPage(1); }} options={reconStatusMap} allLabel={c.filterReconStatusAll} />
              </>
            )}
          </Toolbar>
          <DataTable
            columns={reconColumns} rows={recon.data?.records} loading={recon.isLoading}
            error={recon.error} onRetry={() => recon.refetch()}
            rowKey={(d) => d.diffNo}
            empty={tab === "repair" ? c.emptyRepair : c.emptyRecon}
          />
          <Pagination page={page} size={size} onSize={setSize} total={recon.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {tab === "close" && closeForm && (
        <ConfigCard
          title={c.closeTitle}
          readOnly={!canPay && <ReadOnlyNotice what={c.closeReadOnlyWhat} perm="order:pay:repair" className="mb-3" />}
          notice={c.closeNotice}
          onSave={() => saveClose.mutate()}
          saving={saveClose.isPending}
          canSave={canPay}
          updatedAt={closeRuleQ.data?.updatedAt}
          updatedBy={closeRuleQ.data?.updatedBy}
        >
          <div className="space-y-1">
            <Label htmlFor="cl-unpaid" required>{c.fieldUnpaidMinutes}</Label>
            <Input id="cl-unpaid" className="w-full" disabled={!canPay} value={closeForm.unpaidMinutes}
              onChange={(e) => setCloseField("unpaidMinutes", e.target.value)} />
            <p className="txt-caption text-muted-foreground">
              {fill(c.unpaidHint, { min: MIN_UNPAID_CLOSE_MINUTES, max: MAX_UNPAID_CLOSE_MINUTES })}
            </p>
          </div>
          <div className="space-y-1">
            <Label htmlFor="cl-remind" required>{c.fieldRemindBefore}</Label>
            <Input id="cl-remind" className="w-full" disabled={!canPay} value={closeForm.remindBeforeMinutes}
              onChange={(e) => setCloseField("remindBeforeMinutes", e.target.value)} />
            <p className="txt-caption text-muted-foreground">{c.remindHint}</p>
          </div>
          <div className="space-y-1">
            <div className="flex items-center gap-3">
              <Switch checked={closeForm.autoRefundOnLateCallback} disabled={!canPay}
                aria-label={c.fieldAutoRefund}
                onChange={(v) => setCloseField("autoRefundOnLateCallback", v)} />
              <span className="txt-body">{c.fieldAutoRefund}</span>
            </div>
            <p className="txt-caption text-muted-foreground">{c.autoRefundHint}</p>
          </div>
        </ConfigCard>
      )}

      <Drawer
        open={!!resolving}
        onOpenChange={(o) => !o && setResolving(null)}
        title={resolving ? fill(c.drawerRecon, { no: resolving.diffNo }) : ""}
        desc={resolving ? diffTypeMap[resolving.type].label : undefined}
        width="w-[520px]"
        footer={
          resolving && canPay ? (
            <>
              <Button variant="outline" loading={ignoreMut.isPending} onClick={() => ignoreMut.mutate()}>{c.btnIgnore}</Button>
              <Button loading={resolveMut.isPending} onClick={() => resolveMut.mutate()}>{c.btnResolve}</Button>
            </>
          ) : null
        }
      >
        {resolving && (
          <div>
            <DrawerSection first title={c.secOverview}>
              <FieldGrid>
                <Field className="mb-3" label={c.colBillDate}>{resolving.billDate}</Field>
                <Field className="mb-3" label={c.colChannel}>{resolving.channel}</Field>
                <Field className="mb-3" label={c.colChannelAmount}>{money(resolving.channelAmount)}</Field>
                <Field className="mb-3" label={c.colPlatformAmount}>{money(resolving.platformAmount)}</Field>
                <Field className="mb-3" label={c.colChannelTxn}>{resolving.channelTxnNo ?? c.none}</Field>
                <Field className="mb-3" label={c.colSubOrderNo}>{resolving.orderNo ?? c.none}</Field>
              </FieldGrid>
            </DrawerSection>

            <DrawerSection title={c.secDecide}>
              {resolving.type === "CHANNEL_ONLY" && (
                <div className="mb-4 space-y-1">
                  <Label htmlFor="rc-action" required>{c.fieldAction}</Label>
                  <Select id="rc-action" className="w-full" value={resolveForm.action}
                    onChange={(e) => setResolveForm((p) => ({ ...p, action: e.target.value as RecoverAction | "" }))}>
                    <option value="">{c.actionPick}</option>
                    <option value="CREATE_ORDER">{c.actionCreateOrder}</option>
                    <option value="REFUND">{c.actionRefund}</option>
                  </Select>
                  <p className="txt-caption text-muted-foreground">
                    {fill(c.actionHint, { amount: money(resolving.channelAmount) })}
                  </p>
                </div>
              )}
              <Field className="mb-0" label={c.fieldResolution}>
                <Textarea value={resolveForm.resolution}
                  onChange={(v) => setResolveForm((p) => ({ ...p, resolution: v }))}
                  placeholder={c.resolutionPlaceholder} />
              </Field>
            </DrawerSection>
          </div>
        )}
      </Drawer>

      {dialog}

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current?.orderNo ?? ""}
        desc={current ? fill(c.drawerParent, { no: current.parentNo }) : undefined}
        width="w-[520px]"
      >
        {current && (
          <div>
            <DrawerSection first title={c.secOverview}>
            <FieldGrid>
              <Field className="mb-3" label={c.colStatus}><OrderStatusBadge value={current.status} /></Field>
              <Field className="mb-3" label={c.colPaid}><span className="tabular-nums">{money(current.payAmount)}</span></Field>
              <Field className="mb-3" label={c.colMerchant}>{current.merchantName}</Field>
              <Field className="mb-3" label={c.colCommunity}>{current.communityName}</Field>
              <Field className="mb-3" label={c.colFulfill}><StatusBadge map={fulfillMap} value={current.fulfillType} /></Field>
              <Field className="mb-3" label={c.fieldPickup}>{current.pickupNo || "-"}</Field>
              <Field className="mb-3" label={c.colTraffic}><StatusBadge map={trafficMap} value={current.trafficSource} /></Field>
              <Field className="mb-3" label={c.fieldPaidAt}>{fmtTime(current.paidAt)}</Field>
            </FieldGrid>
            </DrawerSection>

            <DrawerSection title={c.secItems}>
              <div className="space-y-1 txt-body">
                {current.items.map((it) => (
                  <div key={it.skuNo} className="flex items-center justify-between gap-3">
                    <span className="truncate">{it.title}</span>
                    <span className="shrink-0 tabular-nums text-muted-foreground">
                      ×{it.qty} · {money(it.price)}
                    </span>
                  </div>
                ))}
              </div>
            </DrawerSection>

            <DrawerSection title={c.secSiblings} desc={c.secSiblingsDesc}>
              {siblings.isLoading ? (
                <span className="text-muted-foreground">{c.loading}</span>
              ) : (
                <div className="space-y-1">
                  {(siblings.data ?? []).filter((s) => s.orderNo !== current.orderNo).map((s) => (
                    <button
                      key={s.orderNo}
                      type="button"
                      onClick={() => setCurrent(s)}
                      className="flex w-full items-center justify-between gap-3 rounded-field px-2 py-1 text-start transition-colors hover:bg-accent"
                    >
                      <span className="truncate">{s.merchantName}</span>
                      <span className="shrink-0 tabular-nums text-muted-foreground">{s.orderNo}</span>
                    </button>
                  ))}
                  {(siblings.data ?? []).length <= 1 && <span className="text-muted-foreground">{c.noSiblings}</span>}
                </div>
              )}
            </DrawerSection>
          </div>
        )}
      </Drawer>
    </div>
  );
}
