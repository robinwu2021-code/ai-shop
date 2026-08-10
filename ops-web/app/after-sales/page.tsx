"use client";

// 售后治理（矩阵 P-6.1）。矩阵 §七 六条必闭合链路里，「售后赔付」此前是唯一三端整条全缺的。
//
// 责任判定（P-6.1.4）**不单独成页**：判了责任才谈得上赔付归属，
// 拆成两页会出现「裁决完了忘了判责」的空档 —— 所以它是裁决抽屉里的必填项。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { AFTER_SALES_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab } from "@/lib/use-page-tab";
import { LIABILITY_SHARE_TOTAL, MIN_FAST_REFUND_HOURS } from "@/lib/constants";
import { fmtTime, money } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useEditableConfig } from "@/lib/use-editable-config";
import { notify } from "@/lib/notify";
import type { AfterSale, Liability } from "@/lib/types";
import { AfterSaleStatusBadge, useAfterSaleStatusMap, useAfterSaleTypeMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ConfigCard } from "@/components/ui/config-card";
import { Notice } from "@/components/ui/notice";
import { StatRow, Pagination, StatCard } from "@/components/ui/misc";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { TabHeader } from "@/components/ui/tab-header";
import { Textarea } from "@/components/ui/textarea";
import { Toolbar } from "@/components/ui/toolbar";

type Copy = (typeof AFTER_SALES_COPY)["zh"];
const TABS = (c: Copy) => [
  { key: "tickets", label: c.tabTickets },
  { key: "intervene", label: c.tabIntervene },
  { key: "fastrefund", label: c.tabFastRefund },
];

const LIABILITY_OPTIONS = (c: Copy): { value: Liability; label: string; hint: string }[] => [
  { value: "MERCHANT", label: c.liabMerchant, hint: c.liabMerchantHint },
  { value: "PICKUP", label: c.liabPickup, hint: c.liabPickupHint },
  { value: "PLATFORM", label: c.liabPlatform, hint: c.liabPlatformHint },
];

/** 责任方 → 默认出资比例。矩阵 M4 未定，这里只是**填单起点**，裁决人可改。 */
const DEFAULT_SHARE: Record<Liability, { platform: number; merchant: number; pickup: number }> = {
  MERCHANT: { platform: 0, merchant: 100, pickup: 0 },
  PICKUP: { platform: 0, merchant: 0, pickup: 100 },
  PLATFORM: { platform: 100, merchant: 0, pickup: 0 },
};

export default function AfterSalesPage() {
  return <Suspense fallback={null}><AfterSalesInner /></Suspense>;
}

function AfterSalesInner() {
  const c = useCopy(AFTER_SALES_COPY);
  const tabs = TABS(c);
  const liabilityOptions = LIABILITY_OPTIONS(c);
  const qc = useQueryClient();
  const allow = useCan();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); setStatus(""); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("");
  const [current, setCurrent] = useState<AfterSale | null>(null);
  const [form, setForm] = useState<{
    liability: Liability; platform: string; merchant: string; pickup: string; verdict: string; amount: string;
  } | null>(null);

  const canHandle = allow("aftersale:ticket:handle");
  const canApprove = allow("aftersale:refund:approve");
  const typeMap = useAfterSaleTypeMap();
  const statusMap = useAfterSaleStatusMap();

  const listQ = {
    keyword, type, status, page, size,
    intervene: tab === "intervene" ? "1" : "",
  };
  const list = useQuery({
    queryKey: ["after-sales", listQ],
    queryFn: () => api.listAfterSales(listQ),
    enabled: tab !== "fastrefund",
  });
  const rule = useQuery({ queryKey: ["fast-refund"], queryFn: () => api.getFastRefundRule(), enabled: tab === "fastrefund" });

  const decide = useMutation({
    mutationFn: () =>
      api.decideAfterSale({
        asNo: current!.asNo,
        liability: form!.liability,
        share: {
          platform: Number(form!.platform),
          merchant: Number(form!.merchant),
          pickup: Number(form!.pickup),
        },
        verdict: form!.verdict,
        amount: Math.round(Number(form!.amount) * 100),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["after-sales"] });
      setCurrent(null); setForm(null);
      notify.success(c.toastDecided);
    },
  });

  // 表单状态走 useEditableConfig：patch 内部是函数式更新，
  // 连点开关不会丢更新（growth 页踩过那个 bug，这里是同一个模式）
  const { form: editing, set: setField, reset: resetRule } = useEditableConfig(rule.data, (d) => ({
    enabled: d.enabled,
    maxAmount: String(d.maxAmount / 100),
    withinHours: String(d.withinHours),
  }));
  const saveRule = useMutation({
    mutationFn: () =>
      api.saveFastRefundRule({
        enabled: editing!.enabled,
        maxAmount: Math.round(Number(editing!.maxAmount) * 100),
        withinHours: Number(editing!.withinHours),
        categories: rule.data?.categories ?? [],
      }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["fast-refund"] }); resetRule(); notify.success(c.toastRuleSaved); },
  });

  const openDecide = (a: AfterSale) => {
    setCurrent(a);
    const liability = a.liability ?? "MERCHANT";
    const share = a.share ?? DEFAULT_SHARE[liability];
    setForm({
      liability,
      platform: String(share.platform), merchant: String(share.merchant), pickup: String(share.pickup),
      verdict: a.verdict ?? "",
      amount: (a.amount / 100).toFixed(2),
    });
  };

  const shareSum = form ? Number(form.platform) + Number(form.merchant) + Number(form.pickup) : 0;

  const columns: Column<AfterSale>[] = [
    { header: c.colAsNo, cell: (a) => a.asNo, numeric: true, align: "start" },
    { header: c.colOrderNo, cell: (a) => a.orderNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (a) => a.merchantName },
    { header: c.colBuyer, cell: (a) => a.buyerNickname },
    { header: c.colType, cell: (a) => <StatusBadge map={typeMap} value={a.type} /> },
    { header: c.colAmount, cell: (a) => money(a.amount), numeric: true },
    {
      header: c.colReason,
      width: "20rem",
      className: "whitespace-normal",
      cell: (a) => <span className="line-clamp-1 text-muted-foreground">{a.reason}</span>,
    },
    { header: c.colEvidence, cell: (a) => a.evidenceCount, numeric: true },
    {
      header: c.colLiability,
      // 未裁决时留"—"而不是空：空会被读成"判过了但没责任方"
      cell: (a) =>
        a.liability ? <Badge tone="info">{liabilityOptions.find((o) => o.value === a.liability)?.label}</Badge> : <span className="text-muted-foreground">—</span>,
    },
    { header: c.colCreatedAt, cell: (a) => fmtTime(a.createdAt) },
    { header: c.colStatus, cell: (a) => <AfterSaleStatusBadge value={a.status} /> },
    {
      header: c.colActions,
      cell: (a) => (
        <Button size="sm" variant="outline" onClick={() => openDecide(a)}>
          {a.status === "ARBITRATING" && canHandle ? c.actionDecide : c.actionView}
        </Button>
      ),
    },
  ];

  const rows = list.data?.records ?? [];
  const interveneCount = rows.filter((a) => a.status === "ARBITRATING").length;
  const pendingAmount = rows.filter((a) => a.status !== "REFUNDED" && a.status !== "CLOSED").reduce((n, a) => n + a.amount, 0);

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab !== "fastrefund" && !canHandle && (
        <ReadOnlyNotice what={c.readOnlyWhat} perm="aftersale:ticket:handle" note={c.readOnlyNote} className="mb-3" />
      )}

      {tab === "intervene" && (
        <Notice className="mb-3">
          {c.interveneNotice}
        </Notice>
      )}

      {tab !== "fastrefund" && (
        <>
          <StatRow>
            <StatCard label={c.kpiIntervene} value={interveneCount} sub={interveneCount > 0 ? c.kpiInterveneSub : c.kpiInterveneNone} tone={interveneCount > 0 ? "down" : undefined} />
            <StatCard label={c.kpiPending} value={money(pendingAmount)} sub={c.kpiPendingSub} />
            <StatCard label={c.kpiPageCount} value={rows.length} />
          </StatRow>

          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchPlaceholder}>
            <FilterSelect aria-label={c.filterType} value={type} onChange={(v) => { setType(v); setPage(1); }} options={typeMap} allLabel={c.filterTypeAll} />
            {tab === "tickets" && (
              <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={statusMap} allLabel={c.filterStatusAll} />
            )}
          </Toolbar>

          <DataTable
            columns={columns} rows={list.data?.records} loading={list.isLoading}
            error={list.error} onRetry={() => list.refetch()}
            rowKey={(a) => a.asNo}
            empty={tab === "intervene" ? c.emptyIntervene : c.emptyTickets}
          />
          <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {tab === "fastrefund" && (
        <ConfigCard
          title={c.ruleTitle}
          readOnly={!canApprove && (
            <ReadOnlyNotice what={c.ruleReadOnlyWhat} perm="aftersale:refund:approve" note={c.ruleReadOnlyNote} className="mb-3" />
          )}
          notice={c.ruleNotice}
          onSave={() => saveRule.mutate()}
          saving={saveRule.isPending}
          canSave={canApprove}
          updatedAt={rule.data?.updatedAt}
          updatedBy={rule.data?.updatedBy}
        >
          {editing && (
            <>
                <div className="flex items-center gap-3">
                  <Switch
                    checked={editing.enabled}
                    disabled={!canApprove}
                    aria-label={c.ariaEnableFastRefund}
                    onChange={(v) => setField("enabled", v)}
                  />
                  <span className="txt-body">{editing.enabled ? c.enabled : c.disabled}</span>
                </div>
                <div className="space-y-1">
                  <Label htmlFor="fr-amount" required>{c.fieldMaxAmount}</Label>
                  <Input id="fr-amount" className="w-full" disabled={!canApprove} value={editing.maxAmount}
                    onChange={(e) => setField("maxAmount", e.target.value)} />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="fr-hours" required>{c.fieldWithinHours}</Label>
                  <Input id="fr-hours" className="w-full" disabled={!canApprove} value={editing.withinHours}
                    onChange={(e) => setField("withinHours", e.target.value)} />
                  <p className="txt-caption text-muted-foreground">
                    {fill(c.hoursHint, { n: MIN_FAST_REFUND_HOURS })}
                  </p>
                </div>
            </>
          )}
        </ConfigCard>
      )}

      <Drawer
        open={!!current}
        onOpenChange={(o) => { if (!o) { setCurrent(null); setForm(null); } }}
        title={current ? `${current.asNo} · ${current.merchantName}` : ""}
        desc={current ? fill(c.drawerOrder, { no: current.orderNo }) : undefined}
        width="w-[560px]"
        footer={
          current?.status === "ARBITRATING" && canHandle ? (
            <Button loading={decide.isPending} onClick={() => decide.mutate()}>{c.btnConfirmDecide}</Button>
          ) : null
        }
      >
        {current && form && (
          <div>
            <DrawerSection first title={c.secTicket} desc={c.secTicketDesc}>
            <FieldGrid>
              <Field className="mb-3" label={c.colType}><StatusBadge map={typeMap} value={current.type} /></Field>
              <Field className="mb-3" label={c.colStatus}><AfterSaleStatusBadge value={current.status} /></Field>
              <Field className="mb-3" label={c.colBuyer}>{current.buyerNickname}</Field>
              <Field className="mb-3" label={c.colEvidence}>{current.evidenceCount ? fill(c.evidenceCount, { n: current.evidenceCount }) : c.none}</Field>
            </FieldGrid>
            <Field className="mb-0" label={c.fieldReason}><p className="whitespace-pre-wrap">{current.reason}</p></Field>
            </DrawerSection>

            {current.status === "ARBITRATING" && canHandle ? (
              <DrawerSection title={c.secDecide} desc={c.secDecideDesc}>
                <div className="mb-4 space-y-1">
                  <Label htmlFor="as-amount" required>{c.fieldRefundAmount}</Label>
                  <Input id="as-amount" className="w-full" value={form.amount}
                    onChange={(e) => setForm((p) => (p ? { ...p, amount: e.target.value } : p))} />
                  <p className="txt-caption text-muted-foreground">{c.refundAmountHint}</p>
                </div>

                <div className="mb-4 space-y-1">
                  <Label htmlFor="as-liab" required>{c.fieldLiability}</Label>
                  <Select
                    id="as-liab" className="w-full" value={form.liability}
                    onChange={(e) => {
                      const liability = e.target.value as Liability;
                      const d = DEFAULT_SHARE[liability];
                      // 换责任方时同步默认比例：否则会留下"责任在商家、平台出 100%"这种矛盾组合
                      setForm((p) => (p ? { ...p, liability, platform: String(d.platform), merchant: String(d.merchant), pickup: String(d.pickup) } : p));
                    }}
                  >
                    {liabilityOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </Select>
                  <p className="txt-caption text-muted-foreground">
                    {liabilityOptions.find((o) => o.value === form.liability)?.hint}
                  </p>
                </div>

                <div className="mb-1 grid grid-cols-3 gap-3">
                  {([["platform", c.sharePlatform], ["merchant", c.shareMerchant], ["pickup", c.sharePickup]] as const).map(([k, label]) => (
                    <div key={k} className="space-y-1">
                      <Label htmlFor={`as-${k}`}>{label} %</Label>
                      <Input id={`as-${k}`} value={form[k]} onChange={(e) => setForm((p) => (p ? { ...p, [k]: e.target.value } : p))} />
                    </div>
                  ))}
                </div>
                <p className={shareSum === LIABILITY_SHARE_TOTAL ? "mb-4 txt-caption text-muted-foreground" : "mb-4 txt-caption text-[var(--destructive)]"}>
                  {fill(c.shareSum, { sum: shareSum, total: LIABILITY_SHARE_TOTAL })}
                  {shareSum !== LIABILITY_SHARE_TOTAL && c.shareSumBad}
                </p>

                <Field label={c.fieldVerdict}>
                  <Textarea value={form.verdict} onChange={(v) => setForm((p) => (p ? { ...p, verdict: v } : p))}
                    placeholder={c.verdictPlaceholder} />
                </Field>
              </DrawerSection>
            ) : (
              <DrawerSection title={c.secResult}>
                <Field label={c.fieldLiability}>
                  {current.liability ? liabilityOptions.find((o) => o.value === current.liability)?.label : c.liabilityUndecided}
                </Field>
                <Field label={c.fieldShare}>
                  {current.share
                    ? fill(c.shareText, { p: current.share.platform, m: current.share.merchant, k: current.share.pickup })
                    : "—"}
                </Field>
                <Field className={current.refundSplitPending ? undefined : "mb-0"} label={c.fieldVerdict}>{current.verdict || "—"}</Field>
                {current.refundSplitPending && (
                  <Notice>
                    {c.refundSplitPending}
                  </Notice>
                )}
              </DrawerSection>
            )}
          </div>
        )}
      </Drawer>
    </div>
  );
}
