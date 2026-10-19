"use client";

import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill } from "@/lib/use-copy";
import { money } from "@/lib/utils";
import type { Order } from "@/lib/types";
import { OrderStatusBadge } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { Textarea } from "@/components/ui/textarea";
import { IdCell } from "@/components/ui/misc";
import type { OrdersCopy } from "./copy";

export function ProxyCancelDrawer({ order, session, c, onClose, onCancelled }: {
  order: Order; session: string; c: OrdersCopy; onClose: () => void; onCancelled: (session: string) => void;
}) {
  const [reason, setReason] = useState("");
  const impact = useQuery({
    queryKey: ["order-cancel-impact", order.orderNo, session],
    queryFn: () => api.getCancellationImpact(order.orderNo),
    staleTime: 0, gcTime: 0, refetchOnMount: "always",
  });
  const cancel = useMutation({
    mutationFn: () => {
      if (impact.isFetching || impact.isError || !impact.data?.canCancel || !reason.trim()) {
        throw new Error(c.cancelImpactState);
      }
      return api.proxyCancelOrder({ orderNo: order.orderNo, reason: reason.trim() });
    },
    onSuccess: () => onCancelled(session),
  });
  const allowed = !impact.isError && impact.data?.canCancel ? impact.data : null;
  return (
    <Drawer open={!!order} onOpenChange={(open) => !open && onClose()}
      title={order ? fill(c.cancelTitle, { no: order.orderNo }) : ""}
      footer={<Button loading={cancel.isPending}
        disabled={impact.isFetching || !allowed || !reason.trim()} onClick={() => cancel.mutate()}>
        {c.btnProxyCancel}
      </Button>}
    >
      {order && <>
        <DrawerSection first title={c.secOverview}>
          <FieldGrid>
            <Field label={c.colMerchant}>{order.merchantName}</Field>
            <Field label={c.colBuyer}>{order.buyerNickname}</Field>
            <Field label={c.colStatus}><OrderStatusBadge value={order.status} /></Field>
          </FieldGrid>
        </DrawerSection>
        <DrawerSection title={c.cancelImpactTitle}>
          {impact.isFetching ? <p role="status">{c.cancelImpactLoading}</p>
            : impact.isError ? <>
              <p className="text-destructive">{c.cancelImpactFailed}</p>
              <Button variant="outline" onClick={() => void impact.refetch()}>{c.cancelImpactRetry}</Button>
            </> : allowed ? <>
              <Field label={c.cancelImpactTotal}>{money(allowed.totalPayableMinor, allowed.currency)}</Field>
              <Field label={c.cancelImpactPayment}><IdCell value={allowed.payOrderNo} /></Field>
              <div className="grid gap-3">
                {allowed.affectedOrders.map((affected) => <div key={affected.subOrderNo}
                  className="flex items-start justify-between gap-3 rounded-card border p-3">
                  <div className="grid gap-1">
                    <IdCell value={affected.subOrderNo} />
                    <span>{affected.storeName || affected.merchantName}</span>
                    <OrderStatusBadge value={affected.status} />
                  </div>
                  <span className="tabular-nums">{money(affected.payableMinor, allowed.currency)}</span>
                </div>)}
              </div>
            </> : <p className="text-destructive">{impact.data && !impact.data.canCancel && impact.data.blockedCode === "FORBIDDEN"
              ? c.cancelImpactForbidden : c.cancelImpactState}</p>}
        </DrawerSection>
        <DrawerSection title={c.secCancel}>
          <Field label={c.fieldCancelReason}>
            <Textarea value={reason} onChange={setReason} placeholder={c.cancelReasonPlaceholder} rows={3} />
          </Field>
          <p className="txt-caption text-muted-foreground">{c.cancelUnpaidHint}</p>
        </DrawerSection>
      </>}
    </Drawer>
  );
}
