"use client";

// 短信/邮件发送记录 + 测试发送（P-14.3）。
//
// **为什么需要这一页**：发出去之后什么都不留的话，「他到底收没收到」这个问句
// 只能靠翻服务器日志来答，而日志会轮转、会被采集走。
//
// **为什么测试发送要过图形验证码**：它是一个能**指定任意收件人**的入口。
// 运营账号一旦泄漏（或内部人误用），它就是一台群发机 —— 而且发出去的是
// 带平台签名的正规短信，比垃圾短信更能骗到人。
// 光靠权限码挡不住这件事：泄漏的是 token，而验证码要人眼。
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fmtTime } from "@/lib/utils";
import { usePaging } from "@/lib/use-paging";
import { isStubbed, notifyFailReason } from "@/lib/notify-reason";
import { bizLabel, channelLabel } from "@/lib/notify-label";
import type { InAppLog, NotifyChannel, NotifyLog } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { HelpNote } from "@/components/ui/help-note";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { TestSendDrawer } from "./test-send-drawer";
import type { MessageCopy } from "./copy";

export function NotifyLogTab({ c, canWrite }: { c: MessageCopy; canWrite: boolean }) {
  const qc = useQueryClient();
  const [channel, setChannel] = useState("");
  // 供应商筛选只对 PUSH 有意义（个推/FCM/APNs 三家挤在 channel=PUSH，N3 才拆得开）
  const [provider, setProvider] = useState("");
  const [status, setStatus] = useState("");
  const [biz, setBiz] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  /*
   * 收件人是**待提交**的：每敲一个字就查一次，会在「1」「13」「138」上各打一次库，
   * 而这张表只增不删。回车或点「查」才发请求 —— 输入框的值单独存，
   * 提交时才进 queryKey。
   */
  const [targetInput, setTargetInput] = useState("");
  const [target, setTarget] = useState("");
  const [testOpen, setTestOpen] = useState(false);
  /*
   * 外发 / 站内信**分两张表**，不合并。
   * 外发答「发出去了吗」（有失败态、排查去通道后台查回执），
   * 站内信答「他读了吗」（入库即到达，没有失败态）——
   * 合成一列的话，同一个「已发送」在两种语义之间摇摆，而下一步动作完全不同。
   * 放同一页是因为运营找「记录」时只会想到一个地方。
   */
  const [kind, setKind] = useState<"outbound" | "inapp">("outbound");
  // D1：此前完全没有分页 —— 组件不传 page/size，后端默认给 20 条，
  // 于是第 21 条之后的记录在界面上等于不存在
  const { page, setPage, size, setSize } = usePaging();

  const logs = useQuery({
    queryKey: ["notify-logs", channel, provider, status, biz, from, to, target, page, size],
    queryFn: () => api.listNotifyLogs({
      channel: channel || undefined,
      // 供应商只在 PUSH 下发筛：换到别的通道时那个下拉本就藏起来了，值也不带过去
      provider: (channel === "PUSH" && provider) || undefined,
      status: status || undefined,
      bizType: biz || undefined,
      from: from || undefined,
      to: to || undefined,
      target: target || undefined,
      page, size,
    }),
  });

  const submitTarget = () => { setTarget(targetInput.trim()); setPage(1); };

  const inapp = useQuery({
    queryKey: ["inapp-messages", from, to, target, page, size],
    // 站内信的 target 是 receiverNo（平台内部标识），与外发的手机号/邮箱不是一回事，
    // 但输入框共用一个 —— 运营查的都是「这个人」
    queryFn: () => api.listInAppMessages({
      receiverNo: target || undefined,
      from: from || undefined, to: to || undefined, page, size,
    }),
    enabled: kind === "inapp",
  });

  const inappCols: Column<InAppLog>[] = [
    { header: c.nlColTime, cell: (r: InAppLog) => fmtTime(new Date(r.at).toISOString()) },
    { header: c.nlColReceiver, cell: (r: InAppLog) => `${r.receiverType} / ${r.receiverNo}` },
    { header: c.nlColTitle2, cell: (r: InAppLog) => r.title },
    { header: c.nlColTemplate, cell: (r: InAppLog) => r.templateNo ?? "-" },
    {
      header: c.nlColRead,
      // 这一列就是站内信存在的意义：外发记录答不了「他读了吗」
      cell: (r: InAppLog) => r.read
        ? <Badge tone="success">{c.nlRead}</Badge>
        : <Badge tone="muted">{c.nlUnread}</Badge>,
    },
  ];

  // 映射规则在 lib/notify-label.ts（纯函数，有测试守着 —— 本仓只测 lib，
  // 内联在这里的话「WXSUB 被显示成短信」那类缺陷没有任何测试能拦）
  const chLabels = { sms: c.nlSms, mail: c.nlMail, wxsub: c.nlWxsub, push: c.nlPush };
  const bizLabels = {
    otp: c.nlBizOTP, initPwd: c.nlBizInitPwd, resetPwd: c.nlBizResetPwd,
    test: c.nlBizTest, trade: c.nlBizTrade,
  };

  /** 失败原文 → 可读归因文案。规则在 lib/notify-reason.ts（那里的中文是匹配模式，不是文案）。 */
  const reasonOf = (error?: string | null): string | null => {
    const r = notifyFailReason(error);
    if (!r) return null;
    return { CRED: c.nlReasonCred, QUOTA: c.nlReasonQuota,
             TARGET: c.nlReasonTarget, NETWORK: c.nlReasonNetwork }[r];
  };

  const cols: Column<NotifyLog>[] = [
    { header: c.nlColTime, cell: (r: NotifyLog) => fmtTime(r.createdAt) },
    { header: c.nlColChannel, cell: (r: NotifyLog) => channelLabel(r.channel, chLabels) },
    { header: c.nlColBiz, cell: (r: NotifyLog) => bizLabel(r.bizType, bizLabels) },
    { header: c.nlColTarget, cell: (r: NotifyLog) => r.target },
    { header: c.nlColTemplate, cell: (r: NotifyLog) => r.templateCode ?? "-" },
    {
      header: c.nlColStatus,
      cell: (r: NotifyLog) =>
        r.status === "SENT"
          // 桩与真发**必须分得开**：两者都「成功」，但下一步完全不同 ——
          // 一个是去配凭据，一个是去通道后台查回执
          ? isStubbed(r.status, r.providerMsgId)
            ? <div className="space-y-1">
                <Badge tone="warning">{c.nlStubbed}</Badge>
                <div className="txt-caption text-muted-foreground">{c.nlStubbedHint}</div>
              </div>
            : <Badge tone="success">{c.nlSent}</Badge>
          // 失败原因**直接铺在行里**，不折进详情：这一列是这张表存在的理由，
          // 藏一层就等于让人多点一次才能看到最要紧的东西。
          // 归因在上、原文在下：归因回答「该找谁」，原文是排查的唯一凭据，两者都不能省
          : <div className="space-y-1">
              <Badge tone="danger">{c.nlFailed}</Badge>
              {reasonOf(r.error) && (
                <div className="txt-caption font-medium text-foreground">{reasonOf(r.error)}</div>
              )}
              {r.error && <div className="txt-caption text-muted-foreground">{r.error}</div>}
            </div>,
    },
    { header: c.nlColMsgId, cell: (r: NotifyLog) => <span className="txt-caption">{r.providerMsgId ?? "-"}</span> },
    {
      header: c.nlColOperator,
      // 自动发出的（验证码）没有触发人，显示「系统自动」而不是空 ——
      // 空会让人以为数据丢了
      cell: (r: NotifyLog) =>
        r.operatorNo ?? <span className="text-muted-foreground">{c.nlAuto}</span>,
    },
  ];

  return (
    <div className="space-y-4">
      {/* U4：两条说明合成一条 —— 站内信那句降为次要文字，不再单占一个 Notice */}
      <HelpNote>
        {c.nlNotice}
        <div className="mt-2 txt-caption text-muted-foreground">{c.nlInappNotice}</div>
      </HelpNote>

      <div className="flex justify-end gap-2">
        <Button variant="ghost" size="sm"
                onClick={() => void (kind === "outbound" ? logs.refetch() : inapp.refetch())}>
          {c.chRefresh}
        </Button>
        {canWrite && <Button size="sm" onClick={() => setTestOpen(true)}>{c.tsOpen}</Button>}
      </div>

      <Card>
        <CardHeader className="flex-row items-center gap-3">
          {/* 这里原本误用了表格列名「时间」当卡片标题 —— 下面就是起止日筛选，两者叠在一起自相矛盾 */}
          <CardTitle className="me-auto">{c.nlTitle}</CardTitle>
          <Select value={kind} className="w-40"
                  onChange={(e) => { setKind(e.target.value as "outbound" | "inapp"); setPage(1); }}>
            <option value="outbound">{c.nlKindOutbound}</option>
            <option value="inapp">{c.nlKindInapp}</option>
          </Select>
          {/* 通道/用途/状态只对外发有意义：站内信没有通道，也没有失败态 */}
          {kind === "outbound" && <>
          <Select value={channel}
                  onChange={(e) => { setChannel(e.target.value); setProvider(""); setPage(1); }}
                  className="w-36">
            <option value="">{c.nlAll}</option>
            <option value="SMS">{c.nlSms}</option>
            <option value="MAIL">{c.nlMail}</option>
            <option value="WXSUB">{c.nlWxsub}</option>
            <option value="PUSH">{c.nlPush}</option>
          </Select>
          {/* 供应商筛选：只在 PUSH 下出现，个推/FCM/APNs 三家终于拆得开（N3） */}
          {channel === "PUSH" && (
            <Select value={provider} onChange={(e) => { setProvider(e.target.value); setPage(1); }} className="w-32">
              <option value="">{c.nlProviderAll}</option>
              <option value="GETUI">GETUI</option>
              <option value="FCM">FCM</option>
              <option value="APNS">APNS</option>
            </Select>
          )}
          {/* 用途筛选：同一条通道上既有验证码也有交易触达，只按通道筛不够 */}
          <Select value={biz} onChange={(e) => { setBiz(e.target.value); setPage(1); }} className="w-36">
            <option value="">{c.nlColBizAll}</option>
            <option value="OTP">{c.nlBizOTP}</option>
            <option value="TRADE_NOTIFY">{c.nlBizTrade}</option>
            <option value="TEST">{c.nlBizTest}</option>
          </Select>
          <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }} className="w-32">
            <option value="">{c.nlAll}</option>
            <option value="SENT">{c.nlSent}</option>
            <option value="FAILED">{c.nlFailed}</option>
          </Select>
          </>}
        </CardHeader>
        <CardContent className="space-y-3">
          {/* 排查永远是「今天出的事」+「这个人有没有收到」，所以这两个条件放在一起 */}
          <div className="flex flex-wrap items-end gap-2">
            <div className="space-y-1">
              <Label htmlFor="nl-from">{c.nlFrom}</Label>
              <Input id="nl-from" type="date" className="w-40" value={from}
                     onChange={(e) => { setFrom(e.target.value); setPage(1); }} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="nl-to">{c.nlTo}</Label>
              <Input id="nl-to" type="date" className="w-40" value={to}
                     onChange={(e) => { setTo(e.target.value); setPage(1); }} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="nl-target">{c.nlTarget}</Label>
              <Input id="nl-target" className="w-52" value={targetInput}
                     placeholder={c.nlTargetPlaceholder}
                     onChange={(e) => setTargetInput(e.target.value)}
                     onKeyDown={(e) => { if (e.key === "Enter") submitTarget(); }} />
            </div>
            <Button size="sm" variant="ghost" onClick={submitTarget}>{c.nlSearch}</Button>
          </div>
          {/* 两种记录的收件人不是一回事：外发是掩码手机号/邮箱，站内信是 userNo */}
          <Notice tone="info">
            {kind === "outbound" ? c.nlTargetHint : c.nlInappTargetHint}
          </Notice>

          {kind === "outbound" ? (
            <DataTable
              columns={cols}
              rows={logs.data?.records ?? []}
              loading={logs.isLoading}
              rowKey={(r) => r.notifyNo}
              empty={c.nlEmpty}
            />
          ) : (
            <DataTable
              columns={inappCols}
              rows={inapp.data?.records ?? []}
              loading={inapp.isLoading}
              rowKey={(r) => r.messageNo}
              empty={c.nlInappEmpty}
            />
          )}
        </CardContent>
      </Card>

      <Pagination page={page} size={size} onSize={setSize}
                  total={(kind === "outbound" ? logs.data?.total : inapp.data?.total) ?? 0}
                  onPage={setPage} />

      {/* 记录页的模拟发送默认走短信；要试别的通道去对应通道页 —— 
          那里同时能看到该通道的凭据与今日量，排查在一个页面里完成 */}
      <TestSendDrawer c={c} channel="SMS" open={testOpen} onOpenChange={setTestOpen}
                      onSent={() => void qc.invalidateQueries({ queryKey: ["notify-logs"] })} />
    </div>
  );
}
