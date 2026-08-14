import { describe, expect, it } from "vitest";
import { notifyFailReason } from "./notify-reason";

describe("发送失败归因", () => {
  it("认得出四条通道的真实错误原文", () => {
    // 原文取自各 gateway 实际抛出的消息格式
    expect(notifyFailReason("个推鉴权失败：10001 token 过期")).toBe("CRED");
    expect(notifyFailReason("微信拒绝：40001 invalid credential")).toBe("CRED");
    expect(notifyFailReason("个推拒绝：20001 离线推送配额已用尽")).toBe("QUOTA");
    expect(notifyFailReason("微信拒绝：43101 user refuse to accept the msg")).toBe("QUOTA");
    expect(notifyFailReason("短信通道网络失败：Connection reset")).toBe("NETWORK");
  });

  it("★ 匹配不上时返回 null —— 编一个归因比不给更误导", () => {
    // 它会把排查的人引去检查一个没问题的地方
    expect(notifyFailReason("isv.TEMPLATE_MISSING_PARAMETERS 模板变量缺失")).toBeNull();
    expect(notifyFailReason("")).toBeNull();
    expect(notifyFailReason(null)).toBeNull();
    expect(notifyFailReason(undefined)).toBeNull();
  });

  it("凭据类优先于网络类 —— 两者都命中时先让人去查配置", () => {
    // 「token 无效」同时含 token 与 connect 时，先报凭据：
    // 网络类会让人去重试，而凭据错重试一万次也是同一个结果
    expect(notifyFailReason("auth failed while connect to provider")).toBe("CRED");
  });
});
