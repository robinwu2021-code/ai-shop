"use client";

// 单条外发通道的三段式页面（TDD-运营端触达中心 §3.2）。四条通道共用它。
//
// **为什么四条通道同构**：运营排查时的第一个问句永远是「哪条通道没到」，
// 学一次会用四次；每条通道各写一页的话，同一件事（看凭据、试一发、翻记录）
// 在四个地方长得都不一样。
//
// **为什么配置只读**：凭据一律走环境变量（application.yml 顶上的既有约定）。
// 一个能在 Web 上读写生产短信密钥的表单，泄漏一次就是全平台可群发 ——
// 这里给的是「配了没有」，不是密钥本身。唯一例外是微信模板号（§4.2）：
// 它不是凭据，而换模板是运营行为，为它发一次版不合理。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import { isStubbed, notifyFailReason } from "@/lib/notify-reason";
import type { NotifyChannel, NotifyChannelHealth, NotifyLog } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { TestSendDrawer } from "./test-send-drawer";
import type { MessageCopy } from "./copy";

export function ChannelTab({ c, channel, canWrite }:
  { c: MessageCopy; channel: NotifyChannel; canWrite: boolean }) {
  const qc = useQueryClient();

  const health = useQuery({
    queryKey: ["notify-channels"],
    queryFn: () => api.listNotifyChannels(),
  });
  const logs = useQuery({
    queryKey: ["notify-logs", channel, "", ""],
    queryFn: () => api.listNotifyLogs({ channel, size: 10 }),
  });

  const me = health.data?.find((h) => h.channel === channel);
  const [testOpen, setTestOpen] = useState(false);

  return (
    <div className="space-y-4">
      <div className="flex justify-end gap-2">
        {/* 刷新：发完测试第一件事就是看结果（U3） */}
        <Button variant="ghost" size="sm" onClick={() => { void health.refetch(); void logs.refetch(); }}>
          {c.chRefresh}
        </Button>
        {canWrite && <Button size="sm" onClick={() => setTestOpen(true)}>{c.tsOpen}</Button>}
      </div>

      <ChannelStatus c={c} health={me} loading={health.isLoading} />
      <ChannelConfig c={c} health={me} channel={channel} canWrite={canWrite} />
      <ChannelTemplates c={c} channel={channel} />
      <RecentLogs c={c} rows={logs.data?.records ?? []} loading={logs.isLoading} />

      <TestSendDrawer
        c={c} channel={channel} open={testOpen} onOpenChange={setTestOpen}
        onSent={() => { void qc.invalidateQueries({ queryKey: ["notify-logs"] });
                        void qc.invalidateQueries({ queryKey: ["notify-channels"] }); }}
      />
    </div>
  );
}

/** 第一段：这条通道现在能不能用。 */
function ChannelStatus({ c, health, loading }:
  { c: MessageCopy; health?: NotifyChannelHealth; loading: boolean }) {
  if (loading || !health) return null;
  const missing = health.credentials.filter((x) => x.required && !x.present).length;
  return (
    <Card>
      <CardHeader className="flex-row items-center gap-3">
        <CardTitle className="me-auto">{c.chStatus}</CardTitle>
        {health.stub
          ? <Badge tone="warning">{c.chStub}</Badge>
          : <Badge tone="success">{c.chLive}</Badge>}
        {missing > 0 && <Badge tone="danger">{c.chCredMissing}</Badge>}
      </CardHeader>
      <CardContent className="space-y-3">
        {/* 桩不是故障，是默认状态 —— 不说清楚的话运营会以为通道坏了 */}
        <Notice tone={health.stub ? "warning" : "info"}>
          {health.stub ? c.chStubHint : c.chLiveHint}
        </Notice>
        <div className="flex gap-6 txt-body">
          <span>{c.chTodaySent}<b className="ms-1">{health.todaySent}</b></span>
          <span>{c.chTodayFailed}<b className="ms-1">{health.todayFailed}</b></span>
        </div>
      </CardContent>
    </Card>
  );
}

/** 第二段：配置。凭据只读，微信模板号可改。 */
function ChannelConfig({ c, health, channel, canWrite }:
  { c: MessageCopy; health?: NotifyChannelHealth; channel: NotifyChannel; canWrite: boolean }) {
  if (!health) return null;
  return (
    <Card>
      <CardHeader><CardTitle>{c.chConfig}</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <Notice tone="info">{c.chCredNotice}</Notice>

        <div className="space-y-1.5">
          {health.credentials.map((cred) => (
            <div key={cred.envVar} className="flex items-center gap-2 txt-body">
              {cred.present
                ? <Badge tone="success">{c.chPresent}</Badge>
                : <Badge tone={cred.required ? "danger" : "muted"}>{c.chAbsent}</Badge>}
              <code className="txt-caption">{cred.envVar}</code>
            </div>
          ))}
        </div>

        {health.params.length > 0 && (
          <div className="space-y-1 border-t border-border pt-3">
            {health.params.map((p) => (
              <div key={p.key} className="flex gap-2 txt-caption">
                <span className="text-muted-foreground">{p.key}</span>
                <span>{p.value || "—"}</span>
              </div>
            ))}
          </div>
        )}

        {channel === "WXSUB" && <WxTemplateForm c={c} canWrite={canWrite} />}
      </CardContent>
    </Card>
  );
}

/** 微信模板号：唯一一项开放到运营端的通道参数。 */
function WxTemplateForm({ c, canWrite }: { c: MessageCopy; canWrite: boolean }) {
  const qc = useQueryClient();
  const tpl = useQuery({ queryKey: ["wx-templates"], queryFn: () => api.getWxTemplates() });
  const [arrived, setArrived] = useState<string | null>(null);
  const [refunded, setRefunded] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: () => api.saveWxTemplates({
      orderArrived: arrived ?? tpl.data?.orderArrived ?? "",
      refunded: refunded ?? tpl.data?.refunded ?? "",
    }),
    onSuccess: () => {
      notify.success(c.chSaved);
      setArrived(null);
      setRefunded(null);
      void qc.invalidateQueries({ queryKey: ["wx-templates"] });
      void qc.invalidateQueries({ queryKey: ["notify-channels"] });
    },
  });

  return (
    <div className="space-y-3 border-t border-border pt-3">
      <Label>{c.chWxTemplates}</Label>
      {/* 两端不同值 = 前端攒的额度后端查不到，一条也发不出去。这句话必须在输入框旁边 */}
      <Notice tone="warning">{c.chWxTemplatesWarn}</Notice>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="space-y-1.5">
          <Label>{c.chWxTplArrived}</Label>
          <Input disabled={!canWrite}
                 value={arrived ?? tpl.data?.orderArrived ?? ""}
                 onChange={(e) => setArrived(e.target.value)} />
        </div>
        <div className="space-y-1.5">
          <Label>{c.chWxTplRefunded}</Label>
          <Input disabled={!canWrite}
                 value={refunded ?? tpl.data?.refunded ?? ""}
                 onChange={(e) => setRefunded(e.target.value)} />
        </div>
      </div>
      {canWrite && (
        <Button size="sm" onClick={() => save.mutate()} disabled={save.isPending}>
          {c.chSave}
        </Button>
      )}
    </div>
  );
}

/**
 * 本通道的模板。
 *
 * <p><b>两类模板在这里要说清楚差别</b>：短信与微信的正文由通道方报备决定，
 * 库里这份只是副本（改它不会改变发出去的内容）；邮件/推送/站内信是平台自己的模板，
 * 库里这份就是发出去的那份。不说明的话，运营会在短信模板上改半天而什么都没变。
 */
function ChannelTemplates({ c, channel }: { c: MessageCopy; channel: NotifyChannel }) {
  const templates = useQuery({
    queryKey: ["msg-templates"],
    queryFn: () => api.listMsgTemplates({ size: 100 }),
  });
  const mine = (templates.data?.records ?? []).filter((t) => t.channel === channel);
  const providerOwned = channel === "SMS" || channel === "WXSUB";

  return (
    <Card>
      <CardHeader><CardTitle>{c.chTemplates}</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        <Notice tone="info">{providerOwned ? c.chTplFixedHint : c.chTplOwnHint}</Notice>
        {/* 账号类邮件停用后仍会发 —— 这条不写，运营会以为开关能关掉它们 */}
        {channel === "MAIL" && <Notice tone="warning">{c.chTplAccountHint}</Notice>}

        {mine.length === 0 && (
          <div className="txt-body text-muted-foreground">{c.chTplEmpty}</div>
        )}
        {mine.map((t) => (
          /* key 要带上语言：同一个模板号每种语言一行（V145），
             只用 templateNo 的话 React key 会撞车 —— 症状是切换时渲染错行 */
          <div key={`${t.templateNo}:${t.lang ?? ""}`}
               className="space-y-1 border-t border-border pt-3 first:border-t-0 first:pt-0">
            <div className="flex items-center gap-2">
              <span className="txt-body font-medium">{t.name}</span>
              <code className="txt-caption text-muted-foreground">{t.templateNo}</code>
              {/* 不显示语言的话，运营看到的是两条一模一样的模板 */}
              {t.lang && <Badge tone="info">{t.lang}</Badge>}
              {!t.enabled && <Badge tone="muted">{c.chTplDisabled}</Badge>}
            </div>
            <pre className="whitespace-pre-wrap rounded-field bg-secondary p-3 txt-caption">
              {t.content}
            </pre>
            {t.providerTemplateId && (
              <div className="txt-caption text-muted-foreground">
                {c.chTplProvider}：{t.providerTemplateId}
              </div>
            )}
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

/** 最近 10 条。全部记录去发送记录页。 */
function RecentLogs({ c, rows, loading }:
  { c: MessageCopy; rows: NotifyLog[]; loading: boolean }) {
  const reasonText: Record<string, string> = {
    CRED: c.nlReasonCred, QUOTA: c.nlReasonQuota,
    TARGET: c.nlReasonTarget, NETWORK: c.nlReasonNetwork,
  };
  const cols: Column<NotifyLog>[] = [
    { header: c.nlColTime, cell: (r) => fmtTime(r.createdAt) },
    { header: c.nlColTarget, cell: (r) => r.target },
    {
      header: c.nlColStatus,
      cell: (r) => r.status === "SENT"
        // 同发送记录页：没带回通道方 id 的，是桩收下的，不是真发出去了
        ? isStubbed(r.status, r.providerMsgId)
          ? <Badge tone="warning">{c.nlStubbed}</Badge>
          : <Badge tone="success">{c.nlSent}</Badge>
        : <div className="space-y-1">
            <Badge tone="danger">{c.nlFailed}</Badge>
            {notifyFailReason(r.error) && (
              <div className="txt-caption font-medium">{reasonText[notifyFailReason(r.error)!]}</div>
            )}
            {r.error && <div className="txt-caption text-muted-foreground">{r.error}</div>}
          </div>,
    },
  ];
  return (
    <Card>
      <CardHeader><CardTitle>{c.chRecent}</CardTitle></CardHeader>
      <CardContent>
        <DataTable columns={cols} rows={rows} loading={loading}
                   rowKey={(r) => r.notifyNo} empty={c.nlEmpty} />
      </CardContent>
    </Card>
  );
}
