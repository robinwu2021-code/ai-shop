"use client";

// 四条通道的健康度一屏（TDD-运营端触达中心 §3.2）。
//
// **为什么保留 /messages 这个落点**：老书签与 nav 里的 href 都指着它，
// 删掉的话点进来是 404。而总览本身有价值 —— 「今天哪条通道在掉」一眼看完，
// 不必逐个 tab 点过去。
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { NotifyChannelHealth } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import type { MessageCopy } from "./copy";

/** 通道 → 该通道页的 tab key。总览行点进去就是它（D3）。 */
const TAB_OF: Record<string, string> = {
  SMS: "sms", MAIL: "mail", WXSUB: "wxsub", PUSH: "apppush",
};

export function ChannelOverviewTab({ c }: { c: MessageCopy }) {
  const router = useRouter();
  const health = useQuery({
    queryKey: ["notify-channels"],
    queryFn: () => api.listNotifyChannels(),
  });

  const label: Record<string, string> = {
    SMS: c.nlSms, MAIL: c.nlMail, WXSUB: c.nlWxsub, PUSH: c.nlPush,
  };

  const cols: Column<NotifyChannelHealth>[] = [
    { header: c.ovColChannel, cell: (r) => label[r.channel] ?? r.channel },
    {
      header: c.ovColState,
      cell: (r) => r.stub
        ? <Badge tone="warning">{c.chStub}</Badge>
        : <Badge tone="success">{c.chLive}</Badge>,
    },
    {
      header: c.ovColCred,
      // 缺哪一个直接点名：只说「凭据不全」的话，运维还要逐个去猜
      cell: (r) => {
        const missing = r.credentials.filter((x) => x.required && !x.present);
        return missing.length === 0
          ? <Badge tone="success">{c.ovCredOk}</Badge>
          : <div className="space-y-1">
              <Badge tone="danger">{c.chCredMissing}</Badge>
              <div className="txt-caption text-muted-foreground">
                {missing.map((x) => x.envVar).join(", ")}
              </div>
            </div>;
      },
    },
    { header: c.chTodaySent, cell: (r) => r.todaySent },
    {
      header: c.chTodayFailed,
      cell: (r) => r.todayFailed > 0
        ? <span className="font-medium text-destructive">{r.todayFailed}</span>
        : <span>{r.todayFailed}</span>,
    },
    {
      header: "",
      // D3：看到「App 推送 今日失败 2」要能直接点进去，
      // 而不是自己回左侧菜单里找那一条
      cell: (r) => (
        <Button variant="ghost" size="sm"
                onClick={() => router.push(`/messages?tab=${TAB_OF[r.channel] ?? "overview"}`)}>
          {c.ovOpen}
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Notice tone="info">{c.ovNotice}</Notice>
      <Card>
        <CardHeader><CardTitle>{c.ovTitle}</CardTitle></CardHeader>
        <CardContent>
          <DataTable columns={cols} rows={health.data ?? []} loading={health.isLoading}
                     rowKey={(r) => r.channel} empty={c.nlEmpty} />
        </CardContent>
      </Card>
    </div>
  );
}
