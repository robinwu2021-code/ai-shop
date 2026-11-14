"use client";

// 商户 App 回报附带的诊断资料（TDD-商户App问题回报与版本 F7）。
//
// **点开才调接口**：一张回报最多带 300 条请求记录，跟着工单列表一起下发的话，
// 列表每翻一页都要多拉几百 KB，而大多数单子客服根本不展开。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { fmtTime } from "@/lib/utils";
import type { MessageCopy } from "./copy";

/** 以 Z 或 ±hh:mm 结尾 = 服务端已归一成 UTC；否则是 App 送来的设备本地时间原文（服务端不猜时区） */
const HAS_ZONE = /(Z|[+-]\d{2}:\d{2})$/;

export function TicketDiagnostics({ ticketNo, c }: { ticketNo: string; c: MessageCopy }) {
  const [open, setOpen] = useState(false);
  const q = useQuery({
    queryKey: ["ticket-diagnostics", ticketNo],
    queryFn: () => api.ticketDiagnostics(ticketNo),
    enabled: open,
  });

  if (!open) {
    return <Button size="sm" variant="outline" onClick={() => setOpen(true)}>{c.btnShowDiagnostics}</Button>;
  }
  if (q.isLoading) return <p className="txt-caption text-muted-foreground">…</p>;
  if (!q.data?.length) return <p className="txt-caption text-muted-foreground">{c.diagnosticsEmpty}</p>;
  return (
    <div className="space-y-2">
      <p className="txt-caption text-muted-foreground">{c.diagnosticsHint}</p>
      <ol className="max-h-80 space-y-1 overflow-auto font-mono txt-caption">
        {q.data.map((d, i) => (
          <li key={i} className="flex flex-wrap items-center gap-2">
            <span className="tabular-nums text-muted-foreground">
              {d.at == null ? c.diagnosticsNoTime : HAS_ZONE.test(d.at) ? fmtTime(d.at) : d.at}
            </span>
            {d.at != null && !HAS_ZONE.test(d.at) && <Badge tone="warning">{c.diagnosticsLocalTime}</Badge>}
            <span>{d.method}</span>
            <span className="break-all">{d.path}</span>
            <span className="text-muted-foreground">{d.outcome}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}
