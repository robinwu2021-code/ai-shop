"use client";

// 投影链路健康度（M3）。
//
// **与「库存对差」是两件事**：那一页读的是数据（账上有多少、实际有多少），
// 这一页读的是链路（事件投出去了没有）。2026-09-02 的教训正是它们被混成了一个数 ——
// 投递任务停着、一条 SKU_UPSERTED 躺了六个小时，而它在运营端的唯一痕迹是
// 「库存对差」里的「待搬 1 个」。看到那个数的人推断不出「投递链路断了」。
//
// 所以这一页的主角是**结论**，不是条数：每一档指向不同的人。
// 「没人碰过」→ 投递任务没在跑；「一直在失败」→ 消费者在抛异常，去看错误。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fmtTime } from "@/lib/utils";
import type { InvLinkHealth, InvLinkChannel, InvLinkVerdict } from "@/lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { HelpNote } from "@/components/ui/help-note";
import { Skeleton } from "@/components/ui/misc";
import type { InventoryCopy } from "./copy";

const useVerdictMap = (c: InventoryCopy): StatusMap<InvLinkVerdict> => ({
  OK: { label: c.lhOk, tone: "success" },
  // 新鲜积压不是故障：投递每 5 秒一轮，一条在途是常态。
  // 标成告警的话这一页会天天误报，然后没人再看它
  BACKLOG: { label: c.lhBacklog, tone: "muted" },
  DISPATCHER_STALLED: { label: c.lhStalled, tone: "danger" },
  CONSUMER_FAILING: { label: c.lhFailing, tone: "danger" },
});

const useChannelName = (c: InventoryCopy): Record<InvLinkChannel, string> => ({
  PLATFORM_TO_INVENTORY: c.lhChanToInv,
  INVENTORY_TO_PLATFORM: c.lhChanToPlatform,
});

export function LinkHealthTab({ c }: { c: InventoryCopy }) {
  const verdictMap = useVerdictMap(c);
  const channelName = useChannelName(c);
  const q = useQuery({ queryKey: ["inv-link-health"], queryFn: () => api.invLinkHealth() });

  return (
    <>
      <HelpNote title={c.lhHelpTitle}>{c.lhHelp}</HelpNote>

      {q.isPending && (
        <div className="grid gap-4 lg:grid-cols-2">
          <Skeleton className="h-48" /><Skeleton className="h-48" />
        </div>
      )}

      {/* 取数失败要说是取数失败。渲染成「一切正常」是这一页最坏的坏法 */}
      {q.error != null && !q.isPending && (
        <Card><CardContent className="txt-body text-[var(--destructive-ink)]">{c.lhLoadFailed}</CardContent></Card>
      )}

      {!q.isPending && q.error == null && (
        <div className="grid gap-4 lg:grid-cols-2">
          {(q.data ?? []).map((r) => (
            <ChannelCard key={r.channel} row={r} c={c}
              name={channelName[r.channel] ?? r.channel} map={verdictMap} />
          ))}
        </div>
      )}
    </>
  );
}

function ChannelCard({ row, c, name, map }: {
  row: InvLinkHealth;
  c: InventoryCopy;
  name: string;
  map: StatusMap<InvLinkVerdict>;
}) {
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between gap-2">
        <CardTitle>{name}</CardTitle>
        <StatusBadge value={row.verdict} map={map} />
      </CardHeader>
      <CardContent className="grid gap-2">
        {/*
          * 积压条数拆成两栏。**这是整页的信息量所在**：两个投递任务失败时
          * 都把事件留在 PENDING、只加 retry_count，所以 status 那一列答不出
          * 「为什么积压」—— 而这两种情况该找的人完全不同。
          */}
        <Row label={c.lhPending} value={row.pending} />
        <Row label={c.lhNeverTried} value={row.neverTried} hint={c.lhNeverTriedHint} />
        <Row label={c.lhRetrying} value={row.retrying} hint={c.lhRetryingHint} />
        {/* 最老一条的时间才是判据：积压 1 条可能只是正在处理的那一瞬 */}
        <Row label={c.lhOldest} value={row.oldestPendingAt ? fmtTime(row.oldestPendingAt) : "—"} />
        <Row label={c.lhLastSent} value={row.lastSentAt ? fmtTime(row.lastSentAt) : c.lhNeverSent} />
        {row.retrying > 0 && <Row label={c.lhMaxRetry} value={row.maxRetry} />}
        {row.lastError && (
          <div className="mt-1 rounded-md bg-muted/60 p-2">
            <div className="txt-caption text-muted-foreground">{c.lhLastError}</div>
            <div className="txt-caption break-all font-mono">{row.lastError}</div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function Row({ label, value, hint }: { label: string; value: React.ReactNode; hint?: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <span className="txt-body text-muted-foreground">
        {label}
        {hint && <span className="ms-1 txt-caption">{hint}</span>}
      </span>
      <span className="txt-body tabular-nums">{value}</span>
    </div>
  );
}
