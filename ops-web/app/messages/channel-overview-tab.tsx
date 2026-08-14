"use client";

// 四条通道的健康度一屏（TDD-运营端触达中心 §3.2）。
//
// **为什么保留 /messages 这个落点**：老书签与 nav 里的 href 都指着它，
// 删掉的话点进来是 404。而总览本身有价值 —— 「今天哪条通道在掉」一眼看完，
// 不必逐个 tab 点过去。
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { NotifyChannelHealth } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import { Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { notify } from "@/lib/notify";
import { useCan } from "@/lib/use-can";
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

      <DefaultLangCard c={c} />
    </div>
  );
}

/**
 * 平台默认语言。
 *
 * <p><b>放在总览而不是邮件页</b>：它是跨通道的系统设置，不是邮件的配置。
 * 放邮件页的话，以后推送/站内信也要多语言时，运营会去邮件页找一个不属于那里的开关。
 */
function DefaultLangCard({ c }: { c: MessageCopy }) {
  const qc = useQueryClient();
  const canWrite = useCan()("message:template:update");
  const cfg = useQuery({ queryKey: ["notify-default-lang"], queryFn: () => api.getDefaultLang() });

  const save = useMutation({
    mutationFn: (lang: string) => api.saveDefaultLang(lang),
    onSuccess: () => {
      notify.success(c.chSaved);
      void qc.invalidateQueries({ queryKey: ["notify-default-lang"] });
    },
  });

  return (
    <Card>
      <CardHeader><CardTitle>{c.ovLangTitle}</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        {/* 不说清楚的话，运营会以为这是「所有邮件都用这种语言」，然后疑惑为什么有人收到英文 */}
        <Notice tone="info">{c.ovLangHint}</Notice>
        <div className="space-y-1.5">
          <Label htmlFor="ov-lang">{c.ovLangLabel}</Label>
          <Select id="ov-lang" className="w-48" disabled={!canWrite || save.isPending}
                  value={cfg.data?.lang ?? ""}
                  onChange={(e) => save.mutate(e.target.value)}>
            {/* 可选值由后端下发 —— 端上硬编码一份的话，加语言时两边会不同步 */}
            {(cfg.data?.options ?? []).map((o) => <option key={o} value={o}>{o}</option>)}
          </Select>
        </div>
      </CardContent>
    </Card>
  );
}
