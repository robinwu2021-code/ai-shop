// 发送记录的两个码 → 文案映射。
//
// **为什么抽成纯函数**：这两处此前内联在 notify-log-tab.tsx 里，而本仓的测试是
// `environment: "node"`、只测 lib 纯函数（见 vitest.config）—— 内联就等于测不到。
// 而这正是出过事的地方：通道列曾写成 `channel === "MAIL" ? 邮件 : 短信`，
// 接了微信订阅消息与 App 推送之后，**那两条通道的记录被显示成「短信」**。
//
// 那个缺陷当时只能靠 mock 里的四通道样本才看得出来；样本清掉之后，
// 这个文件的测试就是接替它的防线。
//
// **两个函数都对未知码回落原码**，不猜：显示原码的人知道自己看到的是个没见过的值，
// 而显示成某个已知类型的人不知道页面在骗他。

/** 各通道文案由调用方（copy.ts）给，这里只管映射规则 —— lib 不碰 i18n。 */
export interface ChannelLabels {
  sms: string;
  mail: string;
  wxsub: string;
  push: string;
  /** 仅**模板列表**会出现：站内信有模板，但它不进发送记录（见 NotifyChannel 的注释）。 */
  inapp?: string;
}

export interface BizLabels {
  otp: string;
  initPwd: string;
  resetPwd: string;
  test: string;
  trade: string;
}

/** 通道码 → 文案。未知码回落原码。 */
export function channelLabel(channel: string, l: ChannelLabels): string {
  const map: Record<string, string> = {
    SMS: l.sms, MAIL: l.mail, WXSUB: l.wxsub, PUSH: l.push,
    ...(l.inapp ? { INAPP: l.inapp } : {}),
  };
  return map[channel] ?? channel;
}

/** 用途码 → 文案。未知码回落原码。 */
export function bizLabel(bizType: string, l: BizLabels): string {
  const map: Record<string, string> = {
    OTP: l.otp,
    OPS_INIT_PASSWORD: l.initPwd,
    OPS_RESET_PASSWORD: l.resetPwd,
    TEST: l.test,
    TRADE_NOTIFY: l.trade,
  };
  return map[bizType] ?? bizType;
}
