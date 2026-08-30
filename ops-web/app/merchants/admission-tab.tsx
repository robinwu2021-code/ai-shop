"use client";

// 弱主体准入：保证金 / 限品类 / 限额，以及门店经营模式。
//
// 为什么这四样在同一页：平台**无仓、不碰货**，「自营」只是资质代持的外壳。
// 准入矩阵里最弱的一档（小微）没有「入平台仓让平台验货」这条出路 —— 那个仓不存在。
// 平台在法律上是销售主体、承担全部产品责任，却没有任何货物控制手段；
// 这个缺口只能用**准入**和**钱**去补。
//
// 三样必须**同时生效**，所以放在同一个页面上配、不拆开：
//   · 只有保证金 → 成交额不封顶，敞口无上限，那笔钱形同虚设
//   · 只有限额   → 出事没钱赔
//   · 只有限品类 → 非入口类照样出事，只是赔得起
//
// 经营模式也在这页：它决定这家店的钱怎么走、票怎么开，
// 与「让不让他卖」是同一组决定。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime, money } from "@/lib/utils";
import type { AdmissionPolicy, DepositTxn, DepositTxnType, LegalForm, PayQuota, StoreMode } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ConfigCard } from "@/components/ui/config-card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Toolbar } from "@/components/ui/toolbar";
import type { MERCHANTS_COPY } from "./copy";

type Copy = (typeof MERCHANTS_COPY)["zh"];

/** 文案表是扁平的 Record<string,string>，枚举到文案的映射在组件里拼。 */
const txnTypeLabel = (c: Copy): Record<DepositTxnType, string> => ({
  PAY: c.adTxnPay, REFUND: c.adTxnRefund, FREEZE: c.adTxnFreeze,
  UNFREEZE: c.adTxnUnfreeze, DEDUCT: c.adTxnDeduct,
});

/** 0 在这三个字段上是**「不限 / 免缴」**，不是「零元」——直接显示 0 会读成后者。 */
const limitText = (minor: number, unlimited: string) => (minor === 0 ? unlimited : money(minor));

export function AdmissionTab({ c }: { c: Copy }) {
  const txnLabel = txnTypeLabel(c);
  const qc = useQueryClient();
  const [merchantNo, setMerchantNo] = useState("");
  const [queried, setQueried] = useState("");

  const policies = useQuery({ queryKey: ["admission-policies"], queryFn: () => api.admissionPolicies() });
  const deposit = useQuery({
    queryKey: ["deposit", queried], queryFn: () => api.merchantDeposit(queried), enabled: !!queried,
  });
  const txns = useQuery({
    queryKey: ["deposit-txns", queried], queryFn: () => api.depositTxns(queried), enabled: !!queried,
  });
  const modes = useQuery({
    queryKey: ["store-modes", queried], queryFn: () => api.storeModes(queried), enabled: !!queried,
  });

  const [txnType, setTxnType] = useState<DepositTxnType>("PAY");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");

  const addTxn = useMutation({
    mutationFn: () =>
      api.addDepositTxn({
        merchantNo: queried,
        txnType,
        /*
         * 方向由类型决定，**退还与扣划都是负的**。
         *
         * 上一版只对 DEDUCT 取负，于是「退还 2000」发出去是 +2000，
         * 后端 paid = paid + amountMinor 照单全收：实缴从 2000 涨到 4000，
         * 而流水上写着「退还」。两侧都不报错，只有对账时才会发现。
         * 后端现在也按类型校验符号，这里是第一道。
         */
        amountMinor: txnType === "DEDUCT" || txnType === "REFUND"
          ? -Math.abs(Number(amount))
          : Math.abs(Number(amount)),
        reason: reason || undefined,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["deposit", queried] });
      qc.invalidateQueries({ queryKey: ["deposit-txns", queried] });
      setAmount("");
      setReason("");
      notify.success(c.adToastTxnAdded);
    },
  });

  /*
   * 收款额度：**写接口 2026-05 就有了，读接口一直没有**，于是这个入口一直没敢挂 ——
   * 只让人往一个看不见当前值的框里填数，而填小了会把正常商家的货全拦下来。
   * 读接口补上（GET /ops/admission/pay-quotas/{merchantNo}）之后才有这一段。
   */
  const quotas = useQuery({
    queryKey: ["pay-quotas", queried], queryFn: () => api.payQuotas(queried), enabled: !!queried,
  });
  const [quotaStore, setQuotaStore] = useState("");
  const [quotaLimit, setQuotaLimit] = useState("");
  const setQuota = useMutation({
    mutationFn: () => api.setPayQuota({
      merchantNo: queried,
      storeNo: quotaStore || undefined,
      // 界面按元收，接口按分 —— 两边单位不一致是这类字段最常见的一种错，
      // 且错了不报：填 5000 想要 50 元，实际设成 5000 元
      quotaLimitMinor: Math.round(Number(quotaLimit) * 100),
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["pay-quotas", queried] });
      setQuotaLimit("");
      notify.success(c.adToastQuotaSaved);
    },
  });

  const quotaColumns: Column<PayQuota>[] = [
    {
      header: c.adColPayAccount,
      cell: (q) => (q.storeNo ? q.storeNo : <span className="text-muted-foreground">{c.adEntityLevel}</span>),
    },
    { header: c.adColChannel, cell: (q) => q.payChannel ?? "—" },
    { header: c.adColApplyStatus, cell: (q) => q.applyStatus ?? "—" },
    {
      header: c.adColQuotaLimit,
      // **0 不是「额度为零」而是「没设过」**，直接显示 0 会读成前者，
      // 于是运营以为这家被限死了，去改一个本来就不生效的阈值
      cell: (q) => (q.limitMinor === 0
        ? <span className="text-muted-foreground">{c.adQuotaUnset}</span>
        : money(q.limitMinor)),
    },
    { header: c.adColQuotaUsed, cell: (q) => money(q.usedMinor) },
  ];

  /*
   * 资金路径与门店经营模式放在同一页，理由与「保证金/限额/限品类三样同页」一致：
   * 它们是**同一组决定**。
   *   funds_mode     钱先进谁的账户  ← 决定要不要给积分补差
   *   business_mode  谁是销售主体    ← 决定走哪条结算状态机、要不要进项票
   * 分开配的人只会漏配其中一半，而漏配不报错。
   */
  const profile = useQuery({
    queryKey: ["merchant-detail", queried],
    queryFn: () => api.getMerchant(queried),
    enabled: !!queried,
  });
  const setFunds = useMutation({
    mutationFn: (fundsMode: "AGGREGATED" | "DIRECT") =>
      api.setFundsMode({ merchantNo: queried, fundsMode }),
    onSuccess: () => {
      notify.success(c.fundsModeChanged);
      qc.invalidateQueries({ queryKey: ["merchant-detail", queried] });
      qc.invalidateQueries({ queryKey: ["merchants"] });
    },
  });

  const setMode = useMutation({
    mutationFn: (v: { storeNo: string; businessMode: StoreMode["businessMode"] }) =>
      api.setStoreBusinessMode(v),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["store-modes", queried] });
      notify.success(c.adToastModeChanged);
    },
  });

  const formLabel: Record<LegalForm, string> = {
    NATURAL_PERSON: c.adFormMicro,
    INDIVIDUAL: c.adFormIndividual,
    ENTERPRISE: c.adFormEnterprise,
  };

  const policyColumns: Column<AdmissionPolicy>[] = [
    { header: c.adColForm, cell: (p) => formLabel[p.legalForm] },
    { header: c.adColDeposit, cell: (p) => limitText(p.requiredDepositMinor, c.adFree), numeric: true },
    { header: c.adColSingle, cell: (p) => limitText(p.singleOrderLimitMinor, c.adUnlimited), numeric: true },
    { header: c.adColDaily, cell: (p) => limitText(p.dailyAmountLimitMinor, c.adUnlimited), numeric: true },
    {
      header: c.adColBanQualified,
      cell: (p) => (p.banQualifiedCategory === 1
        ? <Badge tone="warning">{c.adBanned}</Badge>
        : <Badge tone="muted">{c.adAllowed}</Badge>),
    },
    { header: c.adColRemark, cell: (p) => p.remark ?? "—" },
  ];

  const txnColumns: Column<DepositTxn>[] = [
    { header: c.adColTxnType, cell: (t) => txnLabel[t.txnType] },
    { header: c.adColAmount, cell: (t) => money(t.amountMinor), numeric: true },
    { header: c.adColBalanceAfter, cell: (t) => money(t.balanceAfterMinor), numeric: true },
    { header: c.adColReason, cell: (t) => t.reason ?? "—" },
    { header: c.adColOperator, cell: (t) => t.operator ?? "—" },
    { header: c.adColTime, cell: (t) => (t.createdAt ? fmtTime(t.createdAt) : "—") },
  ];

  const modeColumns: Column<StoreMode>[] = [
    { header: c.adColStore, cell: (s) => s.storeName },
    {
      header: c.adColMode,
      cell: (s) => (s.businessMode === "SELF_OPERATED"
        ? <Badge tone="warning">{c.adModeSelfOperated}</Badge>
        : s.businessMode === "THIRD_PARTY"
          ? <Badge tone="success">{c.adModeThirdParty}</Badge>
          : <Badge tone="muted">{c.adModeUnset}</Badge>),
    },
    { header: c.adColPayMerchant, cell: (s) => s.payMerchantNo ?? c.adNoPayAccount },
    {
      header: c.adColAction,
      cell: (s) => (
        <div className="flex gap-2">
          <Button
            size="sm" variant="outline" disabled={s.businessMode === "SELF_OPERATED" || setMode.isPending}
            onClick={() => setMode.mutate({ storeNo: s.storeNo, businessMode: "SELF_OPERATED" })}
          >
            {c.adSwitchSelfOperated}
          </Button>
          <Button
            size="sm" variant="outline"
            // 没有收款号就切不了第三方：钱没处去。前端先禁掉，后端还有一道（70012）
            disabled={s.businessMode === "THIRD_PARTY" || !s.payMerchantNo || setMode.isPending}
            title={!s.payMerchantNo ? c.adNoPayAccountHint : undefined}
            onClick={() => setMode.mutate({ storeNo: s.storeNo, businessMode: "THIRD_PARTY" })}
          >
            {c.adSwitchThirdParty}
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Notice tone="warning">{c.adNotice}</Notice>

      <div>
        <div className="mb-2 txt-strong">{c.adPolicyTitle}</div>
        <p className="txt-caption text-muted-foreground mb-2">{c.adPolicyHint}</p>
        <DataTable columns={policyColumns} rows={policies.data ?? []} rowKey={(p) => p.legalForm} />
      </div>

      <Toolbar>
        <Input
          className="w-64" placeholder={c.adSearchPlaceholder}
          value={merchantNo} onChange={(e) => setMerchantNo(e.target.value)}
        />
        <Button onClick={() => setQueried(merchantNo.trim())} disabled={!merchantNo.trim()}>
          {c.adSearch}
        </Button>
      </Toolbar>

      {queried && deposit.data && (
        <ConfigCard title={fill(c.adDepositTitle, { no: queried })}>
          <div className="grid grid-cols-4 gap-3">
            <Stat label={c.adPaid} value={money(deposit.data.paidMinor)} />
            <Stat label={c.adFrozen} value={money(deposit.data.frozenMinor)} />
            <Stat label={c.adAvailable} value={money(deposit.data.availableMinor)} />
            <Stat label={c.adRequired} value={limitText(deposit.data.requiredMinor, c.adFree)} />
          </div>
          <p className="txt-caption text-muted-foreground mt-2">
            {deposit.data.sufficient ? c.adSufficient : c.adInsufficient}
          </p>
          <div className="grid grid-cols-2 gap-3 mt-3">
            <Stat label={c.adColSingle} value={limitText(deposit.data.singleOrderLimitMinor, c.adUnlimited)} />
            <Stat label={c.adColDaily} value={limitText(deposit.data.dailyAmountLimitMinor, c.adUnlimited)} />
          </div>

          <div className="grid grid-cols-3 gap-3 mt-4">
            <div className="space-y-1">
              <Label>{c.adColTxnType}</Label>
              <FilterSelect
                aria-label={c.adColTxnType} value={txnType}
                onChange={(v) => setTxnType(v as DepositTxnType)}
                options={(["PAY", "REFUND", "FREEZE", "UNFREEZE", "DEDUCT"] as DepositTxnType[])
                  .map((t) => ({ value: t, label: txnLabel[t] }))}
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor="ad-amount" required>{c.adFieldAmount}</Label>
              <Input id="ad-amount" value={amount} onChange={(e) => setAmount(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="ad-reason">{c.adFieldReason}</Label>
              <Input id="ad-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
          </div>
          <div className="mt-3">
            <Button onClick={() => addTxn.mutate()} disabled={!amount || addTxn.isPending}>
              {c.adAddTxn}
            </Button>
          </div>
        </ConfigCard>
      )}

      {queried && (
        <div>
          <div className="mb-2 txt-strong">{c.adTxnTitle}</div>
          <p className="txt-caption text-muted-foreground mb-2">{c.adTxnHint}</p>
          <DataTable columns={txnColumns} rows={txns.data ?? []} rowKey={(t) => t.txnNo} />
        </div>
      )}

      {queried && (
        <div>
          <div className="mb-2 txt-strong">{c.fundsModeTitle}</div>
          <p className="txt-caption text-muted-foreground mb-2">{c.fundsModeHint}</p>
          <FilterSelect
            value={profile.data?.fundsMode ?? "AGGREGATED"}
            onChange={(v) => setFunds.mutate(v as "AGGREGATED" | "DIRECT")}
            options={[
              { value: "AGGREGATED", label: c.fundsAggregated },
              { value: "DIRECT", label: c.fundsDirect },
            ]}
          />
        </div>
      )}

      {queried && (
        <div>
          <div className="mb-2 txt-strong">{c.adQuotaTitle}</div>
          <p className="txt-caption text-muted-foreground mb-2">{c.adQuotaHint}</p>
          <DataTable
            columns={quotaColumns}
            rows={quotas.data}
            loading={quotas.isPending}
            error={quotas.error}
            onRetry={() => quotas.refetch()}
            rowKey={(q) => q.storeNo || "__entity__"}
            /* 空 = 还没进过件。**不能写成「暂无额度」**：那句话会把人引去调额度 */
            empty={c.adQuotaNoAccount}
          />
          {/* 没有收款号时不给填框 —— 填了也只会拿到一个 404 */}
          {!!quotas.data?.length && (
            <div className="grid grid-cols-3 gap-3 mt-3 items-end">
              <div className="space-y-1">
                <Label>{c.adColPayAccount}</Label>
                <FilterSelect
                  aria-label={c.adColPayAccount} value={quotaStore}
                  onChange={setQuotaStore}
                  options={quotas.data.map((q) => ({
                    value: q.storeNo, label: q.storeNo || c.adEntityLevel,
                  }))}
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor="ad-quota" required>{c.adQuotaFieldLimit}</Label>
                <Input id="ad-quota" value={quotaLimit} onChange={(e) => setQuotaLimit(e.target.value)} />
              </div>
              <Button onClick={() => setQuota.mutate()} disabled={!quotaLimit || setQuota.isPending}>
                {c.adQuotaSet}
              </Button>
            </div>
          )}
        </div>
      )}

      {queried && (
        <div>
          <div className="mb-2 txt-strong">{c.adModeTitle}</div>
          <p className="txt-caption text-muted-foreground mb-2">{c.adModeHint}</p>
          <DataTable columns={modeColumns} rows={modes.data ?? []} rowKey={(s) => s.storeNo} />
        </div>
      )}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border p-3">
      <div className="txt-caption text-muted-foreground">{label}</div>
      <div className="txt-strong text-lg">{value}</div>
    </div>
  );
}
