"use client";

// 模拟发送抽屉（TDD-触达中心界面优化 §2）。五条通道共用。
//
// **为什么是抽屉不是常驻卡片**：本仓的约定写在 components/ui/popover.tsx 顶部 ——
// 「顺手看一眼/改一下」用 Popover，「进去做一件事」用 Drawer。填收件人、改内容、
// 看预览、输验证码，是典型的「进去做一件事」；常驻在页面上则把首屏占掉，
// 而运营大部分时候来这一页是查记录，不是发测试。
//
// **模板可见 + 自定义可填 + 实时预览**：三段一起给，运营点发送前就知道
// 「这条发出去长什么样」。各通道能填多少不是我们定的，是通道定的（见 lib/notify-template）。
import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { isFreeText, placeholdersOf, renderTemplate, wxSceneOf } from "@/lib/notify-template";
import type { MsgTemplate } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Drawer, DrawerSection } from "@/components/ui/drawer";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Textarea } from "@/components/ui/textarea";
import type { MessageCopy } from "./copy";

/** 站内信不在 NotifyChannel 里（它不进 sys_notify_log），所以这里单独放宽。 */
export type TestChannel = "SMS" | "MAIL" | "WXSUB" | "PUSH" | "INAPP";

const MAX_BODY = 2000;

export function TestSendDrawer({
  c, channel, open, onOpenChange, onSent,
}: {
  c: MessageCopy;
  channel: TestChannel;
  open: boolean;
  onOpenChange: (o: boolean) => void;
  onSent: () => void;
}) {
  const free = isFreeText(channel);

  // 模板从 msg_template 读（V141 种子）。停用的不给选 —— 停用即刻生效是它的既有语义
  const templates = useQuery({
    queryKey: ["msg-templates"],
    queryFn: () => api.listMsgTemplates({ size: 100 }),
    enabled: open,
  });
  const mine = useMemo(
    () => (templates.data?.records ?? []).filter((t) => t.channel === channel && t.enabled),
    [templates.data, channel],
  );

  /*
   * **选中的身份是「模板号 + 语言」**，不是模板号。同一条业务模板每种语言一行（V145）——
   * 只按模板号找的话，选了英文版拿到的仍是中文那条（find 命中第一个）。
   */
  const [templateKey, setTemplateKey] = useState("");
  const keyOf = (t: MsgTemplate) => `${t.templateNo}:${t.lang ?? ""}`;
  const tpl: MsgTemplate | undefined =
    mine.find((t) => keyOf(t) === templateKey) ?? mine[0];

  const [target, setTarget] = useState("");
  const [level, setLevel] = useState("NORMAL");
  const [receiverType, setReceiverType] = useState("OPS");
  const [link, setLink] = useState("/messages");
  const [values, setValues] = useState<Record<string, string>>({});
  const [captchaCode, setCaptchaCode] = useState("");
  /*
   * 预检结果。**在填完收件人时就查**，早于取验证码 ——
   * 「没额度 / 没绑设备」输完 userNo 就能知道，而验证码是一次性的：
   * 等到点发送才告诉他「换个账号」，那张码已经废了，得从头再来一遍。
   */
  const [precheckErr, setPrecheckErr] = useState("");

  const captcha = useQuery({
    queryKey: ["captcha"],
    queryFn: () => api.getCaptcha(),
    // 站内信不过验证码：它发不出平台，骚扰不到外部的人
    enabled: open && channel !== "INAPP",
  });
  const refreshCaptcha = () => { setCaptchaCode(""); void captcha.refetch(); };

  // 换通道/换模板时把上一条的内容清掉 —— 留着会让人以为这次填的就是上次那些
  useEffect(() => { setValues({}); setTarget(""); setCaptchaCode(""); setPrecheckErr(""); },
    [channel, open]);

  const keys = tpl ? placeholdersOf(tpl.content) : [];
  const preview = tpl ? renderTemplate(tpl.content, values) : "";
  const bodyTooLong = (values.body ?? "").length > MAX_BODY;

  const send = useMutation({
    mutationFn: async () => {
      if (channel === "INAPP") {
        return api.testSendInApp({
          receiverType, receiverNo: target.trim(),
          title: values.subject ?? "", body: values.body, link,
        });
      }
      return api.testSendNotify({
        channel, target: target.trim(),
        level: channel === "PUSH" ? level : undefined,
        subject: values.subject, body: values.body,
        // 短信/微信只认 params：通道方收的是模板号 + 参数。
        // 微信再带一个 scene：一条模板对一个场景，后端据此决定发到货还是退款
        params: channel === "WXSUB"
          ? { ...values, scene: wxSceneOf(tpl?.templateNo) } : values,
        captchaId: captcha.data?.captchaId ?? "",
        captchaCode: captchaCode.trim(),
      });
    },
    onSuccess: () => {
      notify.success(c.nlTestOk);
      onSent();
      onOpenChange(false);
    },
    // 验证码一次性：无论成败都得换一张，否则下次提交必报「验证码错误」
    onError: () => refreshCaptcha(),
  });

  const targetLabel = channel === "SMS" ? c.chTargetPhone
    : channel === "MAIL" ? c.chTargetMail
      : channel === "INAPP" ? c.tsReceiverType : c.chTargetUserNo;

  /*
   * 只有微信与推送有可预检的东西（额度 / 设备绑定）。
   * 短信邮件号码对不对只有发出去才知道 —— 给它们也调一次只是白跑一趟。
   */
  const runPrecheck = async () => {
    if ((channel !== "WXSUB" && channel !== "PUSH") || !target.trim()) return;
    try {
      await api.precheckNotifyTarget({
        channel, target: target.trim(),
        scene: channel === "WXSUB" ? wxSceneOf(tpl?.templateNo) : undefined,
      });
      setPrecheckErr("");
    } catch (e) {
      setPrecheckErr(e instanceof Error ? e.message : c.tsPrecheckFailed);
    }
  };

  // 预检没过就别让他往下走：验证码是一次性的，白按一次就得重取
  const canSend = !!target.trim() && !bodyTooLong && !precheckErr
    && (channel === "INAPP" || !!captchaCode.trim());

  return (
    <Drawer
      open={open}
      onOpenChange={onOpenChange}
      title={fill(c.tsTitle, { ch: channelName(c, channel) })}
      desc={c.tsDesc}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>{c.tsCancel}</Button>
          <Button onClick={() => send.mutate()} disabled={!canSend || send.isPending}>
            {c.tsSubmit}
          </Button>
        </div>
      }
    >
      {/* 收件人 */}
      <DrawerSection first title={targetLabel}>
        {channel === "INAPP" && (
          <div className="mb-2 space-y-1.5">
            <Label>{c.tsReceiverType}</Label>
            <Select value={receiverType} onChange={(e) => setReceiverType(e.target.value)}>
              <option value="USER">{c.tsReceiverUser}</option>
              <option value="STAFF">{c.tsReceiverStaff}</option>
              <option value="OPS">{c.tsReceiverOps}</option>
            </Select>
          </div>
        )}
        <Input value={target}
               onChange={(e) => { setTarget(e.target.value); setPrecheckErr(""); }}
               onBlur={() => void runPrecheck()} />
        {/* 预检失败要压在最上面：它说的是「这一发注定白发」，比下面的通道提示更该先看到 */}
        {precheckErr && <Notice tone="danger" className="mt-2">{precheckErr}</Notice>}
        {channel === "WXSUB" && <Notice tone="danger" className="mt-2">{c.chWxQuotaWarn}</Notice>}
        {channel === "PUSH" && <Notice tone="info" className="mt-2">{c.chPushHint}</Notice>}
      </DrawerSection>

      {/* 模板：只读展示 */}
      <DrawerSection title={c.tsTemplate}>
        {mine.length === 0 && <Notice tone="warning">{c.tsTemplateNone}</Notice>}
        {mine.length > 1 && (
          <Select className="mb-2" value={tpl ? keyOf(tpl) : ""}
                  onChange={(e) => { setTemplateKey(e.target.value); setValues({}); }}>
            {mine.map((t) => (
              /* 名字后缀语言：不带的话下拉里会出现两条同名项，选哪个全靠猜 */
              <option key={keyOf(t)} value={keyOf(t)}>
                {t.lang ? `${t.name}（${t.lang}）` : t.name}
              </option>
            ))}
          </Select>
        )}
        {tpl && (
          <>
            <pre className="whitespace-pre-wrap rounded-field bg-secondary p-3 txt-caption">
              {tpl.content}
            </pre>
            {tpl.providerTemplateId && (
              <div className="mt-1 txt-caption text-muted-foreground">
                {tpl.providerTemplateId}
              </div>
            )}
            {/* 短信/微信改不了正文，这句必须写 —— 不写运营会以为是功能没做 */}
            <Notice tone={free ? "info" : "warning"} className="mt-2">
              {free ? c.tsTemplateFree : c.tsTemplateFixed}
            </Notice>
          </>
        )}
      </DrawerSection>

      {/* 自定义部分：占位逐个给输入框 */}
      {tpl && keys.length > 0 && (
        <DrawerSection title={c.tsCustom}>
          <div className="space-y-3">
            {keys.map((k) => (
              <div key={k} className="space-y-1.5">
                <Label>{fieldLabel(c, k)}</Label>
                {k === "body" ? (
                  // Textarea 的 onChange 直接给值（本仓 README 约定），不是 event
                  <Textarea rows={4} value={values[k] ?? ""}
                            onChange={(v) => setValues((p) => ({ ...p, [k]: v }))} />
                ) : (
                  <Input value={values[k] ?? ""}
                         onChange={(e) => setValues((p) => ({ ...p, [k]: e.target.value }))} />
                )}
                {k === "thing2" && <div className="txt-caption text-muted-foreground">{c.tsTipHint}</div>}
                {k === "body" && bodyTooLong && (
                  <div className="txt-caption text-destructive">{c.tsBodyTooLong}</div>
                )}
              </div>
            ))}
            {channel === "INAPP" && (
              <div className="space-y-1.5">
                <Label>{c.tsFieldLink}</Label>
                <Input value={link} onChange={(e) => setLink(e.target.value)} />
              </div>
            )}
            {channel === "PUSH" && (
              <div className="space-y-1.5">
                <Label>{c.chPushLevel}</Label>
                <Select value={level} onChange={(e) => setLevel(e.target.value)}>
                  <option value="NORMAL">{c.chLevelNormal}</option>
                  <option value="RING">{c.chLevelRing}</option>
                </Select>
              </div>
            )}
          </div>
        </DrawerSection>
      )}

      {/* 预览：点发送前就知道发出去长什么样 */}
      {tpl && (
        <DrawerSection title={c.tsPreview}>
          <pre className="whitespace-pre-wrap rounded-field border border-border p-3 txt-body">
            {preview}
          </pre>
        </DrawerSection>
      )}

      {/* 图形验证码：站内信没有 */}
      <DrawerSection title={c.nlTestCaptcha}>
        {channel === "INAPP" ? (
          <Notice tone="info">{c.tsInappNoCaptcha}</Notice>
        ) : (
          <>
            <Notice tone="warning" className="mb-2">{c.nlTestWarn}</Notice>
            <div className="flex items-center gap-2">
              <Input value={captchaCode} onChange={(e) => setCaptchaCode(e.target.value)}
                     placeholder={c.nlTestCaptchaPh} className="w-28" />
              {captcha.data && (
                <button type="button" onClick={refreshCaptcha} title={c.nlTestRefresh}
                        className="rounded-field border border-card-border">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={`data:image/png;base64,${captcha.data.imageBase64}`}
                       alt={c.nlTestCaptcha} className="h-9" />
                </button>
              )}
            </div>
          </>
        )}
      </DrawerSection>
    </Drawer>
  );
}

function channelName(c: MessageCopy, ch: TestChannel): string {
  return { SMS: c.nlSms, MAIL: c.nlMail, WXSUB: c.nlWxsub,
           PUSH: c.nlPush, INAPP: c.channelInbox }[ch];
}

/** 占位名 → 人话。认不出的占位直接显示原名 —— 猜一个好听的名字会掩盖模板写错。 */
function fieldLabel(c: MessageCopy, key: string): string {
  return ({
    code: c.tsFieldCode,
    subject: c.tsFieldSubject,
    body: c.tsFieldBody,
    title: c.tsFieldTitle,
    thing2: c.tsFieldTip,
    /*
     * number1 是**件数**、amount1 是**金额**，不是模板名。
     * 此前这里错用了模板号配置区的标签（「到货通知」「退款通知」）——
     * 运营看到一个叫「到货通知」的输入框，猜不出它要的是几件。
     */
    number1: c.tsFieldCount,
    amount1: c.tsFieldAmount,
  } as Record<string, string>)[key] ?? key;
}
