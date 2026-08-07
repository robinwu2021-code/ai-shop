"use client";

// 发票与个税（矩阵 P-12.2.2 / 12.2.3）。
//
// 两件事同页，因为它们回答的是同一个问题的两半：「这笔钱在税上怎么算」——
// 开票是对外的凭证，代扣是对内的扣除。分两页会让人以为可以只做一半。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { useEditableConfig } from "@/lib/use-editable-config";
import { fmtTime, money } from "@/lib/utils";
import { MAX_TAX_RATE, MINOR_UNIT } from "@/lib/constants";
import type { InvoiceRequest, InvoiceStatus } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ConfigCard } from "@/components/ui/config-card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import type { FinanceCopy } from "./copy";

const useInvoiceStatusMap = (c: FinanceCopy): StatusMap<InvoiceStatus> => ({
  PENDING: { label: c.ivPending, tone: "warning" },
  ISSUED: { label: c.ivIssued, tone: "success" },
  REJECTED: { label: c.ivRejected, tone: "muted" },
});

export function InvoiceTab({ c, canEdit }: { c: FinanceCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const statusMap = useInvoiceStatusMap(c);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [current, setCurrent] = useState<InvoiceRequest | null>(null);
  const [serialNo, setSerialNo] = useState("");
  const [reason, setReason] = useState("");

  const q = { keyword, status, page, size };
  const list = useQuery({ queryKey: ["invoices", q], queryFn: () => api.listInvoiceRequests(q) });
  const taxQ = useQuery({ queryKey: ["tax-rule"], queryFn: () => api.getTaxRule() });

  const done = () => { qc.invalidateQueries({ queryKey: ["invoices"] }); setCurrent(null); };
  const issue = useMutation({
    mutationFn: () => api.issueInvoice({ invoiceNo: current!.invoiceNo, serialNo }),
    onSuccess: () => { done(); notify.success(c.toastIvIssued); },
  });
  const reject = useMutation({
    mutationFn: () => api.rejectInvoice({ invoiceNo: current!.invoiceNo, reason }),
    onSuccess: () => { done(); notify.success(c.toastIvRejected); },
  });

  const { form: taxForm, set: setTaxField, reset: resetTax } = useEditableConfig(taxQ.data, (d) => ({
    threshold: String(d.threshold / MINOR_UNIT),
    rate: String(d.rate / 100),
  }));
  const saveTax = useMutation({
    mutationFn: () =>
      api.saveTaxRule({
        threshold: Math.round(Number(taxForm!.threshold) * MINOR_UNIT),
        rate: Math.round(Number(taxForm!.rate) * 100),
      }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["tax-rule"] }); resetTax(); notify.success(c.toastTaxSaved); },
  });

  const columns: Column<InvoiceRequest>[] = [
    { header: c.colInvoiceNo, cell: (i) => i.invoiceNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (i) => i.merchantName },
    { header: c.colPeriod, cell: (i) => i.period },
    { header: c.colTitleType, cell: (i) => (i.titleType === "COMPANY" ? c.titleCompany : c.titlePersonal) },
    { header: c.colTitle, cell: (i) => i.title },
    {
      header: c.colIvAmount,
      // 超过已结算金额的直接标红：这条不是"待确认"，是不能开
      cell: (i) => (i.amount > i.settledAmount ? <Badge tone="danger">{money(i.amount)}</Badge> : money(i.amount)),
      numeric: true,
    },
    { header: c.colSettled, cell: (i) => money(i.settledAmount), numeric: true },
    { header: c.colIvStatus, cell: (i) => <StatusBadge map={statusMap} value={i.status} /> },
    {
      header: c.colActions,
      cell: (i) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(i); setSerialNo(""); setReason(""); }}>
          {i.status === "PENDING" ? c.actionIssue : c.actionView}
        </Button>
      ),
    },
  ];

  const pending = current?.status === "PENDING";
  const overSettled = !!current && current.amount > current.settledAmount;
  const missingTaxNo = !!current && current.titleType === "COMPANY" && !current.taxNo;

  return (
    <>
      <Notice className="mb-3">{c.invoiceNotice}</Notice>
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchInvoice}>
        <FilterSelect aria-label={c.filterIvStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }}
          options={statusMap} allLabel={c.filterIvStatusAll} />
      </Toolbar>
      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(i) => i.invoiceNo}
        empty={c.emptyInvoice}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />

      {taxForm && (
        <ConfigCard
          className="mt-6"
          title={c.taxTitle}
          readOnly={!canEdit && <ReadOnlyNotice what={c.taxReadOnlyWhat} perm="finance:invoice:read" className="mb-3" />}
          notice={c.taxNotice}
          onSave={() => saveTax.mutate()}
          saving={saveTax.isPending}
          canSave={canEdit}
          updatedAt={taxQ.data?.updatedAt}
          updatedBy={taxQ.data?.updatedBy}
        >
          <div className="space-y-1">
            <Label htmlFor="tax-threshold" required>{c.fieldThreshold}</Label>
            <Input id="tax-threshold" className="w-full" disabled={!canEdit} value={taxForm.threshold}
              onChange={(e) => setTaxField("threshold", e.target.value)} />
            <p className="txt-caption text-muted-foreground">{c.thresholdHint}</p>
          </div>
          <div className="space-y-1">
            <Label htmlFor="tax-rate" required>{c.fieldTaxRate}</Label>
            <Input id="tax-rate" className="w-full" disabled={!canEdit} value={taxForm.rate}
              onChange={(e) => setTaxField("rate", e.target.value)} />
            <p className="txt-caption text-muted-foreground">{fill(c.taxRateHint, { n: MAX_TAX_RATE / 100 })}</p>
          </div>
        </ConfigCard>
      )}

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current ? fill(c.invoiceTitle, { no: current.invoiceNo }) : ""}
        desc={current ? statusMap[current.status].label : undefined}
        width="w-[520px]"
        footer={
          current && canEdit && pending ? (
            <>
              <Button variant="outline" loading={reject.isPending} onClick={() => reject.mutate()}>{c.btnRejectInvoice}</Button>
              <Button loading={issue.isPending} onClick={() => issue.mutate()}>{c.btnIssueInvoice}</Button>
            </>
          ) : null
        }
      >
        {current && (
          <div>
            <DrawerSection first title={c.secIvOverview}>
              <FieldGrid>
                <Field className="mb-3" label={c.colMerchant}>{current.merchantName}</Field>
                <Field className="mb-3" label={c.colPeriod}>{current.period}</Field>
                <Field className="mb-3" label={c.colTitleType}>
                  {current.titleType === "COMPANY" ? c.titleCompany : c.titlePersonal}
                </Field>
                <Field className="mb-3" label={c.colTaxNo}>{current.taxNo ?? c.none}</Field>
                <Field className="mb-3" label={c.colIvAmount}>{money(current.amount)}</Field>
                <Field className="mb-3" label={c.colSettled}>{money(current.settledAmount)}</Field>
              </FieldGrid>
              {overSettled && <p className="txt-caption text-danger">{c.warnOverSettled}</p>}
              {missingTaxNo && <p className="txt-caption text-danger">{c.warnMissingTaxNo}</p>}
            </DrawerSection>

            {pending && (
              <DrawerSection title={c.secIssue}>
                <div className="mb-3 space-y-1">
                  <Label htmlFor="iv-serial" required>{c.fieldSerialNo}</Label>
                  <Input id="iv-serial" className="w-full" value={serialNo}
                    onChange={(e) => setSerialNo(e.target.value)} placeholder={c.serialPlaceholder} />
                  <p className="txt-caption text-muted-foreground">{c.serialHint}</p>
                </div>
                <Field className="mb-0" label={c.fieldRejectReason}>
                  <Textarea value={reason} onChange={setReason} rows={2} placeholder={c.ivRejectPlaceholder} />
                </Field>
              </DrawerSection>
            )}

            {current.serialNo && (
              <DrawerSection title={c.secIssued}>
                <Field className="mb-0" label={c.fieldSerialNo}>{current.serialNo}</Field>
                <p className="mt-1 txt-caption text-muted-foreground">
                  {current.decidedAt ? fmtTime(current.decidedAt) : c.none}
                </p>
              </DrawerSection>
            )}

            {current.remark && (
              <DrawerSection title={c.secIvRemark}>
                <p className="txt-body">{current.remark}</p>
              </DrawerSection>
            )}
          </div>
        )}
      </Drawer>
    </>
  );
}
