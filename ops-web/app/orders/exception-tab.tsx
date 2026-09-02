"use client";

// 异常单处理（矩阵 P-4.1.4）。
//
// 从 page.tsx 拆出来的原因很实际：订单页已经有 6 个 tab，写在一个文件里会到 600 行，
// 而这一块与其它 tab 之间只有「共用文案表」这一条联系。
//
// ⚠️ 这个队列是**实时算出来的视图**（见 contracts/order.ts），所以处置完一条它自己就消失了，
// 不需要「标记已处理」这种动作 —— 那种按钮存在的地方，多半是把视图错做成了表。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime, money } from "@/lib/utils";
import { ORDER_TRANSITIONS } from "@/lib/types";
import type { ExceptionKind, OrderException, OrderStatus } from "@/lib/types";
import { OrderStatusBadge, useOrderStatusMap } from "@/components/status";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { HelpNote } from "@/components/ui/help-note";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import type { OrdersCopy } from "./copy";

/** 异常成因 → 徽标。两类的处置方式不同：一类推状态，一类去查关单任务。 */
const useKindMap = (c: OrdersCopy): StatusMap<ExceptionKind> => ({
  STUCK: { label: c.kindStuck, tone: "warning" },
  PAY_TIMEOUT: { label: c.kindPayTimeout, tone: "danger" },
});

export function ExceptionTab({ c, canModify }: { c: OrdersCopy; canModify: boolean }) {
  const qc = useQueryClient();
  const kindMap = useKindMap(c);
  const statusMap = useOrderStatusMap();
  const [keyword, setKeyword] = useState("");
  const [kind, setKind] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [current, setCurrent] = useState<OrderException | null>(null);
  const [form, setForm] = useState<{ to: OrderStatus | ""; remark: string }>({ to: "", remark: "" });

  const q = { keyword, kind, page, size };
  const list = useQuery({ queryKey: ["order-exceptions", q], queryFn: () => api.listExceptionOrders(q) });
  const history = useQuery({
    queryKey: ["order-interventions", current?.order.orderNo],
    queryFn: () => api.listOrderInterventions(current!.order.orderNo),
    enabled: !!current,
  });

  const intervene = useMutation({
    mutationFn: () =>
      api.interveneOrder({ orderNo: current!.order.orderNo, to: form.to as OrderStatus, remark: form.remark }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["order-exceptions"] });
      qc.invalidateQueries({ queryKey: ["orders"] });
      setCurrent(null);
      notify.success(c.toastIntervened);
    },
  });

  const columns: Column<OrderException>[] = [
    { header: c.colSubOrderNo, cell: (e) => e.order.orderNo, numeric: true, align: "start" },
    { header: c.colKind, cell: (e) => <StatusBadge map={kindMap} value={e.kind} /> },
    { header: c.colStatus, cell: (e) => <OrderStatusBadge value={e.order.status} /> },
    {
      header: c.colStuck,
      numeric: true,
      // 卡了多久是这张表的重点：超阈多少倍决定先看哪条，所以给徽标而不是灰数字
      cell: (e) => <Badge tone="danger">{fill(c.stuckValue, { n: e.stuckMinutes })}</Badge>,
    },
    { header: c.colThreshold, cell: (e) => fill(c.stuckValue, { n: e.thresholdMinutes }), numeric: true },
    { header: c.colMerchant, cell: (e) => e.order.merchantName },
    { header: c.colBuyer, cell: (e) => e.order.buyerNickname },
    { header: c.colPaid, cell: (e) => money(e.order.payAmount), numeric: true },
    {
      header: c.colActions,
      cell: (e) =>
        canModify ? (
          <Button size="sm" variant="outline" onClick={() => { setCurrent(e); setForm({ to: "", remark: "" }); }}>
            {c.actionIntervene}
          </Button>
        ) : <span className="text-muted-foreground">{c.none}</span>,
    },
  ];

  // 只列状态机允许的下一步：把不合法的选项摆出来再报错，是在骗运营点一次
  const nextStates = current ? ORDER_TRANSITIONS[current.order.status] : [];

  return (
    <>
      <HelpNote className="mb-3">{c.exceptionNotice}</HelpNote>
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchException}>
        <FilterSelect aria-label={c.filterKind} value={kind} onChange={(v) => { setKind(v); setPage(1); }}
          options={kindMap} allLabel={c.filterKindAll} />
      </Toolbar>
      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(e) => e.order.orderNo}
        empty={c.emptyException}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current ? fill(c.interveneTitle, { no: current.order.orderNo }) : ""}
        desc={current ? kindMap[current.kind].label : undefined}
        width="w-[520px]"
        footer={
          <Button disabled={!form.to} loading={intervene.isPending} onClick={() => intervene.mutate()}>
            {c.actionIntervene}
          </Button>
        }
      >
        {current && (
          <>
            <DrawerSection first title={c.secOverview}>
              <FieldGrid>
                <Field label={c.colStatus}><OrderStatusBadge value={current.order.status} /></Field>
                <Field label={c.colStuck}>{fill(c.stuckValue, { n: current.stuckMinutes })}</Field>
                <Field label={c.colMerchant}>{current.order.merchantName}</Field>
                <Field label={c.colBuyer}>{current.order.buyerNickname}</Field>
                <Field label={c.colPaid}>{money(current.order.payAmount)}</Field>
                <Field label={c.colCreatedAt}>{fmtTime(current.order.createdAt)}</Field>
              </FieldGrid>
            </DrawerSection>

            <DrawerSection title={c.secIntervene}>
              <div className="mb-4 space-y-1">
                <Label htmlFor="ex-to" required>{c.fieldTo}</Label>
                <Select id="ex-to" className="w-full" value={form.to}
                  onChange={(e) => setForm((p) => ({ ...p, to: e.target.value as OrderStatus | "" }))}>
                  <option value="">{c.toPick}</option>
                  {nextStates.map((s) => <option key={s} value={s}>{statusMap[s].label}</option>)}
                </Select>
                <p className="txt-caption text-muted-foreground">{c.toHint}</p>
              </div>
              <Field className="mb-0" label={c.fieldRemark}>
                <Textarea value={form.remark} onChange={(v) => setForm((p) => ({ ...p, remark: v }))}
                  placeholder={c.remarkPlaceholder} rows={3} />
              </Field>
            </DrawerSection>

            <DrawerSection title={c.secHistory}>
              {history.isLoading && <p className="txt-caption text-muted-foreground">{c.loading}</p>}
              {history.data?.length === 0 && <p className="txt-caption text-muted-foreground">{c.noHistory}</p>}
              <ul className="space-y-2">
                {history.data?.map((h) => (
                  <li key={h.at} className="txt-caption text-muted-foreground">
                    {fmtTime(h.at)} · {h.operator} · {statusMap[h.from]?.label} → {statusMap[h.to]?.label} · {h.remark}
                  </li>
                ))}
              </ul>
            </DrawerSection>
          </>
        )}
      </Drawer>
    </>
  );
}
