"use client";
// 静态收款码（开发计划 M5b.4，F57）：全部门店的码；风控停用（停用后顾客扫不到）。生成在商家端。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PayOpsQrRow } from "@/lib/api/contracts/payops";
import { useCan } from "@/lib/use-can";
import { money, fmtTime } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import { TabHeader } from "@/components/ui/tab-header";
import { usePaging } from "@/lib/use-paging";
import { StatusBadge, useTT } from "../shared";

export default function PayOpsQrCodesPage() {
  const tt = useTT();
  const can = useCan();
  const qc = useQueryClient();
  const [status, setStatus] = useState("ACTIVE");
  const [store, setStore] = useState("");
  const [storeId, setStoreId] = useState<number | undefined>();
  const { page, setPage, size } = usePaging(20);
  const list = useQuery({ queryKey: ["pay-ops-qr-codes", status, storeId, page, size],
    queryFn: () => api.payOpsQrCodes({ status: status || undefined, storeId, page, size }) });
  const disable = useMutation({ mutationFn: (qr: string) => api.payOpsDisableQrCode(qr),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["pay-ops-qr-codes"] }) });

  const columns = [
    { header: tt("码值", "Code"), cell: (c: PayOpsQrRow) => <span dir="ltr" className="font-mono">{c.qrCode}</span> },
    { header: tt("门店", "Store"), cell: (c: PayOpsQrRow) => c.storeId ?? "—" },
    { header: tt("类型", "Kind"), cell: (c: PayOpsQrRow) => c.kind === "TABLE" ? tt(`桌码 ${c.tableRef ?? ""}`, `Table ${c.tableRef ?? ""}`) : tt("店码", "Store") },
    { header: tt("单笔上限", "Per payment"), numeric: true, cell: (c: PayOpsQrRow) => money(c.singleLimitMinor, c.currency) },
    { header: tt("日上限", "Per day"), numeric: true, cell: (c: PayOpsQrRow) => money(c.dailyLimitMinor, c.currency) },
    { header: tt("建立", "Created"), cell: (c: PayOpsQrRow) => fmtTime(c.createdAt) },
    { header: tt("状态", "Status"), cell: (c: PayOpsQrRow) => <StatusBadge status={c.status} /> },
    { header: tt("操作", "Actions"), stickyEnd: true, cell: (c: PayOpsQrRow) => c.status === "ACTIVE" && can("pay:qr:manage")
      ? <Button size="sm" variant="outline" disabled={disable.isPending}
          onClick={() => { if (window.confirm(tt(`停用 ${c.qrCode}？顾客扫了将付不了。`, `Disable ${c.qrCode}? Customers can no longer pay with it.`))) disable.mutate(c.qrCode); }}>
          {tt("风控停用", "Disable")}</Button> : null },
  ];
  return <main className="space-y-3">
    <TabHeader tabs={[{ key: "qr", label: tt("静态收款码", "Static QR codes") }]} value="qr" onChange={() => {}}
      desc={tt("商家贴在店里 / 桌上的码。发现异常收款可以在这里停用。", "Codes merchants put up in store or on tables. Disable one here on suspicious activity.")} />
    <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); const n = Number(store.trim()); setStoreId(store.trim() && Number.isFinite(n) ? n : undefined); setPage(1); }}>
      <label className="flex flex-col gap-1">{tt("状态", "Status")}
        <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
          <option value="ACTIVE">{tt("生效中", "Active")}</option>
          <option value="DISABLED">{tt("已停用", "Disabled")}</option>
          <option value="">{tt("全部", "All")}</option>
        </Select></label>
      <label className="flex flex-col gap-1">{tt("门店 ID", "Store id")}
        <Input dir="ltr" inputMode="numeric" value={store} onChange={(e) => setStore(e.target.value)} /></label>
      <Button variant="outline" type="submit">{tt("查询", "Search")}</Button>
    </form>
    {disable.error && <Notice tone="warning">{disable.error.message}</Notice>}
    <DataTable columns={columns} rows={list.data} loading={list.isPending} error={list.error} onRetry={() => void list.refetch()}
      rowKey={(c) => c.qrCode} empty={tt("没有匹配的收款码。", "No matching codes.")} />
    <div className="flex justify-end gap-2">
      <Button variant="outline" disabled={page === 1} onClick={() => setPage(page - 1)}>{tt("上一页", "Previous")}</Button>
      <Button variant="outline" disabled={(list.data?.length ?? 0) < size} onClick={() => setPage(page + 1)}>{tt("下一页", "Next")}</Button>
    </div>
  </main>;
}
