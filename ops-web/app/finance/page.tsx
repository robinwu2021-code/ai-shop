"use client";

// 结算与资金（矩阵 P-12.1）。它是「按商家拆单 + 分账」链路的收口 ——
// 把商家的报备状态、自提点的服务费率、订单的流量来源、售后的回退标记这四个
// **已存在的字段**接起来。不接上，前面几个域的那些字段就是死的。
//
// 提现与税（P-12.2）矩阵标 P1 二期，本批不做，导航里保持待建。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { FINANCE_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { MAX_SPLIT_RETRY, SETTLE_FREEZE_MIN_DAYS } from "@/lib/constants";
import { money } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useEditableConfig } from "@/lib/use-editable-config";
import { notify } from "@/lib/notify";
import type { AfterSale, Settlement, SplitLog, TrafficSource } from "@/lib/types";
import { SettleStatusBadge, useSettleStatusMap, useTrafficSourceMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
// 提现与发票各自成块 —— 与结算那四个 tab 只共用文案表
import { WithdrawTab } from "./withdraw-tab";
import { InvoiceTab } from "./invoice-tab";
// 费率单独成块：它与结算那几个 tab 只共用文案表，且形状是版本化的、与配置卡完全不同
import { FeeRuleTab } from "./fee-rule-tab";
import { PayChannelTab } from "./pay-channel-tab";
import { SettleBatchTab } from "./settle-batch-tab";
import { DebtTab } from "./debt-tab";
import { ChannelMessageTab } from "./channel-message-tab";
import { PointsTab } from "./points-tab";
import { PointsPolicyTab } from "./points-policy-tab";
import { PayablesTab } from "./payables-tab";
import { PurchaseInvoiceTab } from "./purchase-invoice-tab";
import { BuyerInvoiceTab } from "./buyer-invoice-tab";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { ConfigCard } from "@/components/ui/config-card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { HelpNote } from "@/components/ui/help-note";
import { StatRow, Pagination, StatCard } from "@/components/ui/misc";
import { StatusBadge } from "@/components/ui/status-badge";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";

type Copy = (typeof FINANCE_COPY)["zh"];
const TAB_KEYS = ["settlements", "settle-batches", "splits", "refund-back", "payables",
  "purchase-invoices", "buyer-invoices", "rates", "pay-channels", "debts",
  "points", "points-policy", "withdraw", "invoice", "channel-messages"] as const;

const TRAFFIC_LABEL = (c: Copy): Record<TrafficSource, string> => ({
  MERCHANT_OWNED: c.trafficMerchantOwned,
  PLATFORM: c.trafficPlatform,
  INVITE: c.trafficInvite,
  CHANNEL: c.trafficChannel,
});

/** 费率以万分比存，展示成百分比 —— 财务说的是「5%」不是「500 个万分点」。 */
const pct = (bp: number) => `${(bp / 100).toFixed(2)}%`;

export default function FinancePage() {
  return <Suspense fallback={null}><FinanceInner /></Suspense>;
}

function FinanceInner() {
  const c = useCopy(FINANCE_COPY);
  const tabs = useNavTabs("/finance", TAB_KEYS);
  const trafficLabel = TRAFFIC_LABEL(c);
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); setStatus(""); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");

  const canExecute = allow("finance:settle:execute");
  const canEditRate = allow("finance:rate:update");
  /* 保证金抵扣动的是商家本金，与「看看谁欠着」不是一类权限 */
  const canPayout = allow("finance:payout:execute");
  const canWithdraw = allow("finance:withdraw:approve");
  const canInvoice = allow("finance:invoice:read");
  /*
   * **读与写分开。** 这一屏上两处写口（个税规则 PUT、开票抬头 POST）后端判的都是
   * `finance:invoice:verify`，而此前界面用 `finance:invoice:read` 决定保存键亮不亮 ——
   * 于是只有读权限的人看到一枚**可点的保存键，点下去 403**。
   * 与后端同一套判据，是这个仓库反复强调的那条：两处不同就会出现「按钮亮着、点了报错」。
   */
  const canInvoiceWrite = allow("finance:invoice:verify");
  const settleStatusMap = useSettleStatusMap();
  const trafficMap = useTrafficSourceMap();

  // 后端这两个查询不分页：结算单按子单一张，量大但筛选维度窄，
  // 先给筛选、分页等有量之后再说 —— 造一个假的分页壳比没有分页更误导
  const settleQ = { status: status || undefined, merchantNo: keyword || undefined };
  const settlements = useQuery({ queryKey: ["settlements", settleQ], queryFn: () => api.listSettlements(settleQ), enabled: tab === "settlements" });
  const splitQ = { settleNo: keyword || undefined };
  const splits = useQuery({ queryKey: ["splits", splitQ], queryFn: () => api.listSplitRecords(splitQ), enabled: tab === "splits" });
  const backs = useQuery({ queryKey: ["refund-backs"], queryFn: () => api.listRefundSplitBacks(), enabled: tab === "refund-back" });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["settlements"] });
    qc.invalidateQueries({ queryKey: ["refund-backs"] });
    qc.invalidateQueries({ queryKey: ["after-sales"] });
  };

  const execBack = useMutation({
    mutationFn: (asNo: string) => api.executeRefundSplitBack(asNo),
    onSuccess: () => { invalidate(); notify.success(c.toastRefundBack); },
  });

  const settleColumns: Column<Settlement>[] = [
    { header: c.colSettleNo, cell: (s) => s.settleNo, numeric: true, align: "start" },
    { header: c.colOrder, cell: (s) => s.subOrderNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (s) => s.merchantNo },
    {
      header: c.colMode,
      // 两条轨道的状态含义完全不同，不标出模式就看不懂状态
      cell: (s) => (s.businessMode === "SELF_OPERATED"
        ? <Badge tone="warning">{c.modeSelfOperated}</Badge>
        : <Badge tone="success">{c.modeThirdParty}</Badge>),
    },
    {
      header: c.colTraffic,
      // R16：自带客流零佣金。列表里必须能看出「这单为什么不抽佣」
      cell: (s) => <StatusBadge map={trafficMap} value={s.trafficSource as TrafficSource} />,
    },
    { header: c.colFeeRate, cell: (s) => pct(s.commissionRate), numeric: true },
    { header: c.colGross, cell: (s) => money(s.grossMinor), numeric: true },
    { header: c.colPlatformFee, cell: (s) => money(s.commissionMinor), numeric: true },
    { header: c.colServiceFee, cell: (s) => money(s.serviceFeeMinor), numeric: true },
    { header: c.colNet, cell: (s) => money(s.netMinor), numeric: true },
    {
      header: c.colPayMerchant,
      // 空 = 生成时进件还没走完。钱是欠着的，不是不存在 —— 这一列让人看得见欠在哪
      cell: (s) => s.payMerchantNo ?? <Badge tone="warning">{c.noPayAccount}</Badge>,
    },
    { header: c.colStatus, cell: (s) => <SettleStatusBadge value={s.status} /> },
  ];

  const splitColumns: Column<SplitLog>[] = [
    { header: c.colSettleNo, cell: (r) => r.settleNo, numeric: true, align: "start" },
    { header: c.colOrder, cell: (r) => r.subOrderNo, numeric: true, align: "start" },
    {
      header: c.colAction,
      // 补差与分账方向相反：补差往二级商户里放钱，分账从里面拿平台应收
      cell: (r) => <Badge tone={r.splitAction.startsWith("SUBSIDY") ? "info" : "muted"}>{r.splitAction}</Badge>,
    },
    { header: c.colGrossAmount, cell: (r) => money(r.amountMinor), numeric: true },
    {
      header: c.colResult,
      cell: (r) => (r.result === "SUCCESS"
        ? <Badge tone="success">{c.resultOk}</Badge>
        : <Badge tone="danger">{c.resultFail}</Badge>),
    },
    // 失败原因是这张表存在的意义 —— 「为什么这单没分成」的答案就在这一列
    { header: c.colMessage, cell: (r) => r.message ?? "—" },
  ];

  const backColumns: Column<AfterSale>[] = [
    { header: c.colAsNo, cell: (a) => a.afterSaleNo, numeric: true, align: "start" },
    { header: c.colOrderNo, cell: (a) => a.orderNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (a) => a.merchantName },
    { header: c.colRefundAmount, cell: (a) => money(a.refundMinor), numeric: true },
    {
      header: c.colShare,
      // 回退要按裁决时判的比例分摊，所以这里必须显示它 —— 否则财务只能回去翻售后单
      cell: (a) =>
        a.share
          ? fill(c.shareText, { p: a.share.platform, m: a.share.merchant, k: a.share.pickup })
          : <span className="text-[var(--destructive)]">{c.shareUndecided}</span>,
    },
    { header: c.colVerdict, cell: (a) => a.verdict ?? "—", className: "whitespace-normal", width: "20rem" },
    {
      header: c.colActions,
      cell: (a) =>
        canExecute ? (
          <Button
            size="sm"
            onClick={async () => {
              const ok = await confirm({
                title: fill(c.confirmBackTitle, { no: a.afterSaleNo }),
                desc: fill(c.confirmBackDesc, { amount: money(a.refundMinor) }),
                danger: true, confirmText: c.confirmBackOk,
              });
              if (ok) execBack.mutate(a.afterSaleNo);
            }}
          >
            {c.btnExecuteBack}
          </Button>
        ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  const rows: Settlement[] = settlements.data?.records ?? [];
  /** 「还没到商家手上的钱」：分账未完成 + 自营未付款，两条轨道的未结都算进来。 */
  const settled = new Set(["SPLIT", "PAID", "REVERSED"]);
  const pendingAmount = rows.filter((s) => !settled.has(s.status)).reduce((n, s) => n + s.netMinor, 0);

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab !== "rates" && tab !== "pay-channels" && tab !== "settle-batches"
        && tab !== "debts" && !canExecute && (
        <ReadOnlyNotice what={c.readOnlyWhat} perm="finance:settle:execute" note={c.readOnlyNote} className="mb-3" />
      )}

      {tab === "settlements" && (
        <>
          <StatRow>
            <StatCard label={c.kpiPending} value={money(pendingAmount)} sub={c.kpiPendingSub} />
            <StatCard label={c.kpiPageCount} value={rows.length} />
            <StatCard
              label={c.kpiMaxed}
              // 转人工的单：它们不会自己好，必须有人去看
              value={rows.filter((s) => s.status === "MANUAL").length}
              sub={c.kpiMaxedSub}
              tone={rows.some((s) => s.status === "MANUAL") ? "down" : undefined}
            />
          </StatRow>
          <HelpNote className="mb-3">
            {c.settleNotice}
          </HelpNote>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchSettle}>
            <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={settleStatusMap} allLabel={c.filterStatusAll} />
          </Toolbar>
          <DataTable
            columns={settleColumns} rows={settlements.data?.records ?? []} loading={settlements.isLoading}
            error={settlements.error} onRetry={() => settlements.refetch()}
            rowKey={(s) => s.settleNo}
            empty={c.emptySettle}
          />
        </>
      )}

      {tab === "splits" && (
        <>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchSplit} />
          <DataTable
            columns={splitColumns} rows={splits.data?.records ?? []} loading={splits.isLoading}
            error={splits.error} onRetry={() => splits.refetch()}
            rowKey={(r) => r.requestNo}
            empty={c.emptySplit}
          />
        </>
      )}

      {tab === "refund-back" && (
        <>
          <HelpNote className="mb-3">
            {c.refundBackNotice}
          </HelpNote>
          <DataTable
            columns={backColumns}
            /*
             * `?? []` 而不是直接传：这条端点后端还没有（refund-split-backs 整域未开工，
             * 见 ops-endpoint-exists 的 UNBUILT_DOMAINS），真接口下拿到的是 404 的错误响应，
             * 直接传给表格就是 `rows.filter is not a function` —— **整页崩**，
             * 而它本该只是这一个 tab 显示空态。
             */
            rows={backs.data ?? []} loading={backs.isLoading}
            error={backs.error} onRetry={() => backs.refetch()}
            rowKey={(a) => a.afterSaleNo}
            empty={c.emptyRefundBack}
          />
        </>
      )}

      {tab === "withdraw" && (
        <>
          {!canWithdraw && <ReadOnlyNotice what={c.withdrawReadOnlyWhat} perm="finance:withdraw:approve" className="mb-3" />}
          <WithdrawTab c={c} canApprove={canWithdraw} />
        </>
      )}

      {tab === "invoice" && <InvoiceTab c={c} canEdit={canInvoice} canWrite={canInvoiceWrite} />}

      {tab === "rates" && <FeeRuleTab c={c} canEdit={canEditRate} />}

      {tab === "pay-channels" && <PayChannelTab c={c} canEdit={canEditRate} />}
      {tab === "settle-batches" && <SettleBatchTab c={c} canExecute={canExecute} />}
      {tab === "debts" && <DebtTab c={c} canExecute={canPayout} />}
      {tab === "channel-messages" && <ChannelMessageTab c={c} />}
      {/*
        自营应付那一整条。**后端十个端点早已实现，此前运营端零入口** ——
        而这是今天唯一真能把钱付出去的路（第三方走分账，而分账网关是桩）。
        「登记付款」单独要 finance:payout:execute：制单与付款分权，
        虽然今天两个码都在 FINANCE 一个角色上（见 Perms 的注释），
        但界面按码判，将来拆角色时不用再改这里。
      */}
      {tab === "payables" && (
        <PayablesTab c={c} canEdit={allow("finance:settle:execute")}
          canPay={allow("finance:payout:execute")} />
      )}
      {tab === "purchase-invoices" && (
        <PurchaseInvoiceTab c={c} canVerify={allow("finance:invoice:verify")} />
      )}
      {tab === "buyer-invoices" && (
        <BuyerInvoiceTab c={c} canIssue={allow("finance:invoice:verify")} />
      )}

      {tab === "points" && <PointsTab c={c} />}

      {/* 端开关。**写权限用 settle:execute** —— 它决定用户在某个端能不能拿到/用掉积分，
          而积分是平台对用户的负债，不是营销活动 */}
      {tab === "points-policy" && <PointsPolicyTab c={c} canEdit={allow("finance:settle:execute")} />}

      {dialog}
    </div>
  );
}
