"use client";

// 团购与求团（矩阵 P-8）。C 端已实现，B/P 两端此前全缺 ——
// 用户能发起求团，但没人能指派商家报价，功能是断的。
//
// 报价治理遵循 ADR-003：**不做事前审核**，靠锁价 + 改价公示 + 信用约束。
// 所以这页没有「报价审核」，只有「指派、改价留痕、毁约记录」。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { GROUPS_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab } from "@/lib/use-page-tab";
import { MAX_MERCHANT_BREACH, MAX_QUOTE_PRICE_CHANGES } from "@/lib/constants";
import { fmtTime, money } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { DemandOrder, GroupCampaign, Quote } from "@/lib/types";
import { DemandStatusBadge, GroupStatusBadge, useDemandStatusMap, useGroupStatusMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { Progress } from "@/components/ui/progress";
import { TabHeader } from "@/components/ui/tab-header";
import { Textarea } from "@/components/ui/textarea";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";

type Copy = (typeof GROUPS_COPY)["zh"];
const TABS = (c: Copy) => [
  { key: "campaigns", label: c.tabCampaigns },
  { key: "demands", label: c.tabDemands },
  { key: "quotes", label: c.tabQuotes },
];

export default function GroupsPage() {
  return <Suspense fallback={null}><GroupsInner /></Suspense>;
}

function GroupsInner() {
  const c = useCopy(GROUPS_COPY);
  const tabs = TABS(c);
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); setStatus(""); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [auditing, setAuditing] = useState<GroupCampaign | null>(null);
  const [rejectReason, setRejectReason] = useState("");
  const [assigning, setAssigning] = useState<DemandOrder | null>(null);
  // 可指派商家来自真实列表，不再在页面里写死候选池 ——
  // 写死的那份既是**数据混进了代码**，也让页面在英文下漏出中文店名。
  // 只在指派抽屉打开时查（size 100：一期候选池很小，够用且不分页）。
  const merchants = useQuery({
    queryKey: ["merchants", "assignable"],
    queryFn: () => api.listMerchants({ size: 100, status: "APPROVED" }),
    enabled: !!assigning,
  });

  const [assignForm, setAssignForm] = useState({ merchantNo: "M903", price: "", minQty: "10", validTo: "2026-08-20T16:00:00Z" });
  const [priceEdit, setPriceEdit] = useState<{ quoteNo: string; value: string } | null>(null);

  const canAudit = allow("group:campaign:audit");
  const canAssign = allow("group:demand:assign");
  const groupStatusMap = useGroupStatusMap();
  const demandStatusMap = useDemandStatusMap();

  const groupQ = { keyword, status, page, size };
  const groups = useQuery({ queryKey: ["groups", groupQ], queryFn: () => api.listGroupCampaigns(groupQ), enabled: tab === "campaigns" });
  const demandQ = { keyword, status, page, size };
  const demands = useQuery({ queryKey: ["demands", demandQ], queryFn: () => api.listDemands(demandQ), enabled: tab === "demands" });
  const quoteQ = { keyword, page, size };
  const quotes = useQuery({ queryKey: ["quotes", quoteQ], queryFn: () => api.listQuotes(quoteQ), enabled: tab === "quotes" });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["groups"] });
    qc.invalidateQueries({ queryKey: ["demands"] });
    qc.invalidateQueries({ queryKey: ["quotes"] });
  };

  const audit = useMutation({
    mutationFn: (v: { groupNo: string; pass: boolean; reason?: string }) => api.auditGroupCampaign(v.groupNo, v.pass, v.reason),
    onSuccess: (g) => {
      invalidate(); setAuditing(null); setRejectReason("");
      notify.success(g.status === "RUNNING" ? c.toastAuditPassed : c.toastAuditRejected);
    },
  });

  const assign = useMutation({
    mutationFn: () =>
      api.assignQuote({
        demandNo: assigning!.demandNo,
        merchantNo: assignForm.merchantNo,
        price: Math.round(Number(assignForm.price) * 100),
        minQty: Number(assignForm.minQty),
        validTo: assignForm.validTo,
      }),
    onSuccess: () => { invalidate(); setAssigning(null); notify.success(c.toastAssigned); },
  });

  const changePrice = useMutation({
    mutationFn: (v: { quoteNo: string; price: number }) => api.changeQuotePrice(v.quoteNo, v.price),
    onSuccess: (q) => {
      invalidate(); setPriceEdit(null);
      notify.success(fill(c.toastPriceChanged, { n: q.priceChanges }));
    },
  });

  const breach = useMutation({
    mutationFn: (quoteNo: string) => api.markQuoteBreached(quoteNo),
    onSuccess: () => { invalidate(); notify.success(c.toastBreachLogged); },
  });

  const groupColumns: Column<GroupCampaign>[] = [
    { header: c.colGroupNo, cell: (g) => g.groupNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (g) => g.merchantName },
    { header: c.colSku, cell: (g) => g.skuTitle },
    { header: c.colOriginPrice, cell: (g) => money(g.originPrice), numeric: true },
    {
      header: c.colGroupPrice,
      numeric: true,
      // 折扣力度直接算出来：运营判「这个团有没有诚意」看的是这个数，不是两个价
      cell: (g) => (
        <span>
          {money(g.groupPrice)}
          <span className="ms-1 text-muted-foreground">
            （{Math.round((1 - g.groupPrice / g.originPrice) * 100)}% off）
          </span>
        </span>
      ),
    },
    {
      header: c.colProgress,
      width: "13rem",
      cell: (g) => (
        <div className="flex items-center gap-2">
          <Progress value={g.joined} total={g.minCount} showText={false} className="w-20" />
          <span className="tabular-nums text-muted-foreground">{g.joined} / {g.minCount}</span>
        </div>
      ),
    },
    { header: c.colEndAt, cell: (g) => fmtTime(g.endAt) },
    { header: c.colStatus, cell: (g) => <GroupStatusBadge value={g.status} /> },
    {
      header: c.colActions,
      cell: (g) =>
        g.status === "PENDING" && canAudit ? (
          <Button size="sm" variant="outline" onClick={() => { setAuditing(g); setRejectReason(""); }}>{c.actionAudit}</Button>
        ) : <span className="text-muted-foreground">—</span>,
    },
  ];

  const demandColumns: Column<DemandOrder>[] = [
    { header: c.colDemandNo, cell: (d) => d.demandNo, numeric: true, align: "start" },
    { header: c.colDemand, cell: (d) => d.title, className: "whitespace-normal", width: "20rem" },
    { header: c.colInitiator, cell: (d) => d.initiatorNickname },
    { header: c.colCommunity, cell: (d) => d.communityName },
    // +1 人数是撮合的依据：人多才值得让商家去备货
    { header: c.colPlusOne, cell: (d) => d.plusOneCount, numeric: true },
    { header: c.colQuoteCount, cell: (d) => d.quoteCount, numeric: true },
    { header: c.colCreatedAt, cell: (d) => fmtTime(d.createdAt) },
    { header: c.colStatus, cell: (d) => <DemandStatusBadge value={d.status} /> },
    {
      header: c.colActions,
      cell: (d) =>
        canAssign && (d.status === "OPEN" || d.status === "QUOTING") ? (
          <Button size="sm" onClick={() => { setAssigning(d); setAssignForm({ merchantNo: "M903", price: "", minQty: "10", validTo: "2026-08-20T16:00:00Z" }); }}>
            {c.actionAssign}
          </Button>
        ) : <span className="text-muted-foreground">—</span>,
    },
  ];

  const quoteColumns: Column<Quote>[] = [
    { header: c.colQuoteNo, cell: (q) => q.quoteNo, numeric: true, align: "start" },
    { header: c.colDemand, cell: (q) => q.demandTitle, className: "whitespace-normal", width: "18rem" },
    { header: c.colMerchant, cell: (q) => q.merchantName },
    {
      header: c.colUnitPrice,
      numeric: true,
      cell: (q) =>
        priceEdit?.quoteNo === q.quoteNo ? (
          <span className="flex items-center justify-end gap-1">
            <Input className="w-24" value={priceEdit.value} aria-label={c.ariaNewPrice}
              onChange={(e) => setPriceEdit({ quoteNo: q.quoteNo, value: e.target.value })} />
            <Button size="sm" onClick={() => changePrice.mutate({ quoteNo: q.quoteNo, price: Math.round(Number(priceEdit.value) * 100) })}>{c.save}</Button>
            <Button size="sm" variant="ghost" onClick={() => setPriceEdit(null)}>{c.cancel}</Button>
          </span>
        ) : (
          <button
            type="button"
            disabled={!canAssign}
            className="rounded-field px-1 tabular-nums transition-colors hover:bg-accent disabled:cursor-default disabled:hover:bg-transparent"
            onClick={() => setPriceEdit({ quoteNo: q.quoteNo, value: (q.price / 100).toFixed(2) })}
          >
            {money(q.price)}
          </button>
        ),
    },
    { header: c.colMinQty, cell: (q) => q.minQty, numeric: true },
    {
      header: c.colPriceChanges,
      numeric: true,
      // 改价本身不违规（ADR-003），但次数是信号：到上限就锁，且列表里要看得见
      cell: (q) =>
        q.priceChanges >= MAX_QUOTE_PRICE_CHANGES
          ? <Badge tone="danger">{fill(c.priceLocked, { n: q.priceChanges })}</Badge>
          : q.priceChanges > 0
            ? <Badge tone="warning">{q.priceChanges}</Badge>
            : <span className="text-muted-foreground">0</span>,
    },
    { header: c.colValidTo, cell: (q) => fmtTime(q.validTo) },
    { header: c.colBreach, cell: (q) => (q.breached ? <Badge tone="danger">{c.breached}</Badge> : <span className="text-muted-foreground">{c.notBreached}</span>) },
    {
      header: c.colActions,
      cell: (q) =>
        canAssign && !q.breached ? (
          <Button
            size="sm" variant="outline"
            onClick={async () => {
              const ok = await confirm({
                title: fill(c.confirmBreachTitle, { name: q.merchantName }),
                desc: c.confirmBreachDesc,
                danger: true,
                confirmText: c.confirmBreachOk,
              });
              if (ok) breach.mutate(q.quoteNo);
            }}
          >
            {c.actionBreach}
          </Button>
        ) : <span className="text-muted-foreground">—</span>,
    },
  ];

  const activeList = tab === "campaigns" ? groups : tab === "demands" ? demands : quotes;

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "campaigns" && !canAudit && (
        <ReadOnlyNotice what={c.readOnlyAuditWhat} perm="group:campaign:audit" note={c.readOnlyAuditNote} className="mb-3" />
      )}
      {tab !== "campaigns" && !canAssign && (
        <ReadOnlyNotice what={c.readOnlyMatchWhat} perm="group:demand:assign" note={c.readOnlyMatchNote} className="mb-3" />
      )}

      {tab === "demands" && (
        <Notice className="mb-3">
          {c.demandNotice}
        </Notice>
      )}
      {tab === "quotes" && (
        <Notice className="mb-3">
          {fill(c.quoteNotice, { max: MAX_QUOTE_PRICE_CHANGES, breach: MAX_MERCHANT_BREACH })}
        </Notice>
      )}

      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); setPage(1); }}
        searchPlaceholder={
          tab === "campaigns" ? c.searchCampaigns
            : tab === "demands" ? c.searchDemands
              : c.searchQuotes
        }
      >
        {tab === "campaigns" && (
          <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={groupStatusMap} allLabel={c.filterStatusAll} />
        )}
        {tab === "demands" && (
          <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={demandStatusMap} allLabel={c.filterStatusAll} />
        )}
      </Toolbar>

      {tab === "campaigns" && (
        <DataTable
          columns={groupColumns} rows={groups.data?.records} loading={groups.isLoading}
          error={groups.error} onRetry={() => groups.refetch()}
          rowKey={(g) => g.groupNo}
          empty={c.emptyCampaigns}
        />
      )}
      {tab === "demands" && (
        <DataTable
          columns={demandColumns} rows={demands.data?.records} loading={demands.isLoading}
          error={demands.error} onRetry={() => demands.refetch()}
          rowKey={(d) => d.demandNo}
          empty={c.emptyDemands}
        />
      )}
      {tab === "quotes" && (
        <DataTable
          columns={quoteColumns} rows={quotes.data?.records} loading={quotes.isLoading}
          error={quotes.error} onRetry={() => quotes.refetch()}
          rowKey={(q) => q.quoteNo}
          empty={c.emptyQuotes}
        />
      )}

      <Pagination page={page} size={size} onSize={setSize} total={activeList.data?.total ?? 0} onPage={setPage} />

      {/* 团审核 */}
      <Drawer
        open={!!auditing}
        onOpenChange={(o) => !o && setAuditing(null)}
        title={auditing ? `${auditing.merchantName} · ${auditing.skuTitle}` : ""}
        desc={auditing?.groupNo}
        footer={
          auditing && canAudit ? (
            <>
              <Button variant="outline" onClick={() => audit.mutate({ groupNo: auditing.groupNo, pass: false, reason: rejectReason })}>{c.btnReject}</Button>
              <Button onClick={() => audit.mutate({ groupNo: auditing.groupNo, pass: true })}>{c.btnPass}</Button>
            </>
          ) : null
        }
      >
        {auditing && (
          <div>
            <FieldGrid>
              <Field className="mb-3" label={c.colOriginPrice}>{money(auditing.originPrice)}</Field>
              <Field className="mb-3" label={c.colGroupPrice}>{money(auditing.groupPrice)}</Field>
              <Field className="mb-3" label={c.fieldMinCount}>{auditing.minCount}</Field>
              <Field className="mb-3" label={c.fieldEndAt}>{fmtTime(auditing.endAt)}</Field>
            </FieldGrid>
            <Notice>
              {c.auditHint}
            </Notice>
            <Field className="mt-4" label={c.fieldRejectReason}>
              <Textarea value={rejectReason} onChange={setRejectReason} placeholder={c.rejectPlaceholder} />
            </Field>
          </div>
        )}
      </Drawer>

      {/* 指派报价 */}
      <Drawer
        open={!!assigning}
        onOpenChange={(o) => !o && setAssigning(null)}
        title={assigning ? fill(c.assignTitle, { title: assigning.title }) : ""}
        desc={assigning ? fill(c.assignDesc, { community: assigning.communityName, n: assigning.plusOneCount }) : undefined}
        footer={assigning ? <Button loading={assign.isPending} onClick={() => assign.mutate()}>{c.btnConfirmAssign}</Button> : null}
      >
        {assigning && (
          <div className="space-y-4">
            <div className="space-y-1">
              <Label htmlFor="as-merchant" required>{c.fieldAssignMerchant}</Label>
              <Select id="as-merchant" className="w-full" value={assignForm.merchantNo}
                onChange={(e) => setAssignForm({ ...assignForm, merchantNo: e.target.value })}>
                {(merchants.data?.records ?? []).map((m) => <option key={m.merchantNo} value={m.merchantNo}>{m.name}</option>)}
              </Select>
              <p className="txt-caption text-muted-foreground">
                {c.assignHint}
              </p>
            </div>
            <div className="space-y-1">
              <Label htmlFor="as-price" required>{c.fieldAssignPrice}</Label>
              <Input id="as-price" className="w-full" value={assignForm.price}
                onChange={(e) => setAssignForm({ ...assignForm, price: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="as-qty" required>{c.fieldAssignQty}</Label>
              <Input id="as-qty" className="w-full" value={assignForm.minQty}
                onChange={(e) => setAssignForm({ ...assignForm, minQty: e.target.value })} />
            </div>
          </div>
        )}
      </Drawer>

      {dialog}
    </div>
  );
}
