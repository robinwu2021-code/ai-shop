import { describe, expect, it } from "vitest";
import { bizLabel, channelLabel, type BizLabels, type ChannelLabels } from "./notify-label";

const CH: ChannelLabels = { sms: "短信", mail: "邮件", wxsub: "微信订阅消息", push: "App 推送" };
const BIZ: BizLabels = {
  otp: "验证码", initPwd: "账号初始密码", resetPwd: "密码重置",
  test: "测试发送", trade: "交易触达",
};

describe("发送记录的码 → 文案", () => {
  it("★★ 微信订阅消息与 App 推送不能被显示成「短信」", () => {
    // 这条守的是一个真实出过的缺陷：通道列曾是 `channel === "MAIL" ? 邮件 : 短信`，
    // 于是接进来的 WXSUB / PUSH 记录全被标成短信 —— 运营按这张表排查
    // 「短信怎么这么多」，查到的是一批根本不是短信的东西。
    expect(channelLabel("WXSUB", CH)).toBe("微信订阅消息");
    expect(channelLabel("PUSH", CH)).toBe("App 推送");
    expect(channelLabel("WXSUB", CH)).not.toBe(CH.sms);
    expect(channelLabel("PUSH", CH)).not.toBe(CH.sms);
  });

  it("四条通道各自映射正确", () => {
    expect(channelLabel("SMS", CH)).toBe("短信");
    expect(channelLabel("MAIL", CH)).toBe("邮件");
  });

  it("★ 未知通道回落原码，不猜成某个已知类型", () => {
    // 显示原码的人知道自己看到的是个没见过的值；
    // 显示成已知类型的人不知道页面在骗他
    expect(channelLabel("VOICE", CH)).toBe("VOICE");
    expect(channelLabel("", CH)).toBe("");
  });

  it("用途码含 TRADE_NOTIFY（事件驱动的交易触达）", () => {
    expect(bizLabel("TRADE_NOTIFY", BIZ)).toBe("交易触达");
    expect(bizLabel("OTP", BIZ)).toBe("验证码");
  });

  it("★ 未知用途码同样回落原码", () => {
    expect(bizLabel("MARKETING_BLAST", BIZ)).toBe("MARKETING_BLAST");
  });
});
