"use client";

// 渠道报文（O1）。
//
// **这一页的读者是出事那天在排查的人。** 所以它不是一张报表：
// 默认给最近的，筛法按「结论」而不是按「时间范围」——
// 出事时想看的是被拒的那几次，不是这一周的全部。
//
// ⚠️ 两处必须说出来，不然会把人引向错的结论：
//   · 报文**已脱敏**，不能拿去重放验签 —— 第一个拿它核签名的人会以为我方验签实现有 bug；
//   · 按单号筛会**滤掉验签失败的行** —— 那种情况我方根本拿不到单号，
//     而那恰恰是最该看的一类。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { ChannelMessage } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import type { FinanceCopy } from "./copy";

const OUTCOMES = ["", "RECEIVED", "ACCEPTED", "REJECTED", "OK", "FAILED"] as const;
const TYPES = ["", "CALLBACK", "SEND"] as const;

export function ChannelMessageTab({ c }: { c: FinanceCopy }) {
  const [outcome, setOutcome] = useState("");
  const [msgType, setMsgType] = useState("");
  const [bizNo, setBizNo] = useState("");

  const q = useQuery({
    queryKey: ["channel-messages", outcome, msgType, bizNo],
    queryFn: () => api.channelMessages({
      outcome: outcome || undefined,
      msgType: msgType || undefined,
      bizNo: bizNo.trim() || undefined,
    }),
  });

  const rows = q.data?.records ?? [];

  const columns: Column<ChannelMessage>[] = [
    { header: c.cmTime, cell: (m) => (
      <span className="tabular-nums text-[12px]">
        {m.createdAt ? new Date(m.createdAt).toLocaleString() : "—"}
      </span>
    ) },
    { header: c.cmChannel, cell: (m) => m.payChannel },
    { header: c.cmType, cell: (m) => c[`cm${m.msgType}` as keyof FinanceCopy] as string ?? m.msgType },
    { header: c.cmApi, cell: (m) => <span className="font-mono text-[12px]">{m.api}</span> },
    {
      header: c.cmBizNo,
      // 无单号不留空白：空白读起来像「这一列没数据」，而它其实是一条信息
      cell: (m) => (m.bizNo
        ? <span className="font-mono text-[12px]">{m.bizNo}</span>
        : <span className="text-[12px] text-muted-foreground">{c.cmNoBizNo}</span>),
    },
    {
      header: c.cmOutcome,
      cell: (m) => (
        <Badge tone={m.outcome === "ACCEPTED" || m.outcome === "OK" ? "success"
          : m.outcome === "RECEIVED" ? "muted" : "danger"}>
          {c[`cm${m.outcome}` as keyof FinanceCopy] as string ?? m.outcome}
        </Badge>
      ),
    },
    {
      header: c.cmReason,
      // 原因是这一页的重点，给它最多的宽度
      cell: (m) => <span className="text-[12px]">{m.reason ?? "—"}</span>,
    },
  ];

  return (
    <div className="space-y-3">
      <p className="text-[13px] text-muted-foreground">{c.cmHint}</p>

      {/* 口径由服务端给，不在端上写死 —— 将来报文能重放了，改的是后端那一行 */}
      {q.data?.note && <Notice tone="warning">{q.data.note}</Notice>}

      <div className="flex flex-wrap items-center gap-2">
        <select
          className="focus-ring h-[var(--ctl-h)] rounded-input border border-border bg-background px-2 text-[13px]"
          value={msgType}
          onChange={(e) => setMsgType(e.target.value)}
        >
          {TYPES.map((t) => (
            <option key={t} value={t}>
              {t ? (c[`cm${t}` as keyof FinanceCopy] as string) : c.cmAll}
            </option>
          ))}
        </select>
        <select
          className="focus-ring h-[var(--ctl-h)] rounded-input border border-border bg-background px-2 text-[13px]"
          value={outcome}
          onChange={(e) => setOutcome(e.target.value)}
        >
          {OUTCOMES.map((o) => (
            <option key={o} value={o}>
              {o ? (c[`cm${o}` as keyof FinanceCopy] as string) : c.cmAll}
            </option>
          ))}
        </select>
        <Input
          className="w-56"
          placeholder={c.cmBizNo}
          value={bizNo}
          onChange={(e) => setBizNo(e.target.value)}
        />
      </div>

      {/* 只在真的按单号筛时才提示 —— 常驻的话它会变成背景噪声 */}
      {bizNo.trim() && <Notice tone="warning">{c.cmBizNoWarn}</Notice>}

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(m) => m.messageNo}
        loading={q.isLoading}
        error={q.error}
        onRetry={() => q.refetch()}
        empty={c.cmEmpty}
      />
    </div>
  );
}
