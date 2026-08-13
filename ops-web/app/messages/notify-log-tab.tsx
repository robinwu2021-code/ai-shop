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
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import type { NotifyChannel, NotifyLog } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import type { MessageCopy } from "./copy";

export function NotifyLogTab({ c, canWrite }: { c: MessageCopy; canWrite: boolean }) {
  const qc = useQueryClient();
  const [channel, setChannel] = useState("");
  const [status, setStatus] = useState("");

  const logs = useQuery({
    queryKey: ["notify-logs", channel, status],
    queryFn: () => api.listNotifyLogs({ channel: channel || undefined, status: status || undefined }),
  });

  const bizLabel = (t: string) =>
    ({ OTP: c.nlBizOTP, OPS_INIT_PASSWORD: c.nlBizInitPwd,
       OPS_RESET_PASSWORD: c.nlBizResetPwd, TEST: c.nlBizTest } as Record<string, string>)[t] ?? t;

  const cols: Column<NotifyLog>[] = [
    { header: c.nlColTime, cell: (r: NotifyLog) => fmtTime(r.createdAt) },
    { header: c.nlColChannel, cell: (r: NotifyLog) => (r.channel === "MAIL" ? c.nlMail : c.nlSms) },
    { header: c.nlColBiz, cell: (r: NotifyLog) => bizLabel(r.bizType) },
    { header: c.nlColTarget, cell: (r: NotifyLog) => r.target },
    { header: c.nlColTemplate, cell: (r: NotifyLog) => r.templateCode ?? "-" },
    {
      header: c.nlColStatus,
      cell: (r: NotifyLog) =>
        r.status === "SENT"
          ? <Badge tone="success">{c.nlSent}</Badge>
          // 失败原因**直接铺在行里**，不折进详情：这一列是这张表存在的理由，
          // 藏一层就等于让人多点一次才能看到最要紧的东西
          : <div className="space-y-1">
              <Badge tone="danger">{c.nlFailed}</Badge>
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
      <Notice tone="info">{c.nlNotice}</Notice>

      {canWrite && <TestSendCard c={c} onSent={() => qc.invalidateQueries({ queryKey: ["notify-logs"] })} />}

      <Card>
        <CardHeader className="flex-row items-center gap-3">
          <CardTitle className="me-auto">{c.nlColTime}</CardTitle>
          <Select value={channel} onChange={(e) => setChannel(e.target.value)} className="w-32">
            <option value="">{c.nlAll}</option>
            <option value="SMS">{c.nlSms}</option>
            <option value="MAIL">{c.nlMail}</option>
          </Select>
          <Select value={status} onChange={(e) => setStatus(e.target.value)} className="w-32">
            <option value="">{c.nlAll}</option>
            <option value="SENT">{c.nlSent}</option>
            <option value="FAILED">{c.nlFailed}</option>
          </Select>
        </CardHeader>
        <CardContent>
          <DataTable
            columns={cols}
            rows={logs.data?.records ?? []}
            loading={logs.isLoading}
            rowKey={(r) => r.notifyNo}
            empty={c.nlEmpty}
          />
        </CardContent>
      </Card>
    </div>
  );
}

/** 测试发送。三道闸里的图形验证码这一道在这里，另外两道在后端。 */
function TestSendCard({ c, onSent }: { c: MessageCopy; onSent: () => void }) {
  const [channel, setChannel] = useState<NotifyChannel>("SMS");
  const [target, setTarget] = useState("");
  const [code, setCode] = useState("");

  const captcha = useQuery({ queryKey: ["captcha"], queryFn: () => api.getCaptcha() });
  const refresh = () => { setCode(""); captcha.refetch(); };

  const send = useMutation({
    mutationFn: () => api.testSendNotify({
      channel, target: target.trim(),
      captchaId: captcha.data?.captchaId ?? "", captchaCode: code.trim(),
    }),
    onSuccess: () => {
      notify.success(c.nlTestOk);
      setTarget("");
      // **无论成败都换一张**：验证码在服务端是一次性的，用过就没了。
      // 不换的话下一次提交必然报「验证码错误」，而用户看到的是自己刚输对过的那张图
      refresh();
      onSent();
    },
    onError: () => refresh(),
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>{c.nlTestTitle}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="txt-body text-muted-foreground">{c.nlTestDesc}</p>
        <Notice tone="warning">{c.nlTestWarn}</Notice>

        <div className="grid gap-4 sm:grid-cols-3">
          <div className="space-y-1.5">
            <Label>{c.nlTestChannel}</Label>
            <Select value={channel} onChange={(e) => setChannel(e.target.value as NotifyChannel)}>
              <option value="SMS">{c.nlSms}</option>
              <option value="MAIL">{c.nlMail}</option>
            </Select>
          </div>
          <div className="space-y-1.5">
            <Label>{c.nlTestTarget}</Label>
            <Input value={target} onChange={(e) => setTarget(e.target.value)}
                   placeholder={c.nlTestTargetPh} />
          </div>
          <div className="space-y-1.5">
            <Label>{c.nlTestCaptcha}</Label>
            <div className="flex items-center gap-2">
              <Input value={code} onChange={(e) => setCode(e.target.value)}
                     placeholder={c.nlTestCaptchaPh} className="w-28" />
              {captcha.data && (
                <button type="button" onClick={refresh} title={c.nlTestRefresh}
                        className="rounded-field border border-card-border">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={`data:image/png;base64,${captcha.data.imageBase64}`}
                       alt={c.nlTestCaptcha} className="h-9" />
                </button>
              )}
            </div>
          </div>
        </div>

        <Button
          onClick={() => send.mutate()}
          disabled={!target.trim() || !code.trim() || send.isPending}
        >
          {c.nlTestSend}
        </Button>
      </CardContent>
    </Card>
  );
}
