/**
 * 微信客服的接入参数：**回落要整体判**。
 *
 * <p>`wx.openCustomerServiceChat` 拿半截参数（只有 corpId、或只有 url）调过去，
 * 失败是**静默**的 —— 界面上与「压根没配」长得一模一样。所以判「配好了没有」
 * 必须两个一起看；一个一个判的写法会让只配一半的环境走进微信客服那一支，
 * 然后点了没反应，而日志里什么都没有。
 *
 * <p>这两个值还必须**随冷启动一起拿到**：那个 API 在 iOS 上要求由用户手势
 * 直接触发，点的时候现拉配置会被判「并非点击触发」；Android 却能过 ——
 * 于是那样写的代码只在 iOS 真机上现形。这里断的是 store 在冷启动那一跳就存住了它。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

const bootstrapConfig = vi.fn();
vi.mock("@/api", () => ({ api: { bootstrapConfig: () => bootstrapConfig() } }));

import { useConfigStore } from "@/stores/config";

describe("微信客服的接入参数", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bootstrapConfig.mockReset();
  });

  it("★★★ 配齐了：冷启动那一跳就存住，点的时候不用再拉", async () => {
    bootstrapConfig.mockResolvedValue({
      features: {},
      customerService: { corpId: "ww123", url: "https://work.weixin.qq.com/kfid/kfc1" },
    });
    const s = useConfigStore();
    await s.load();

    expect(s.customerService.corpId).toBe("ww123");
    expect(s.customerService.url).toBe("https://work.weixin.qq.com/kfid/kfc1");
    expect(s.wxKfReady).toBe(true);
  });

  it("★★★ 只配一半必须回落 —— 半截参数调过去是静默失败", async () => {
    bootstrapConfig.mockResolvedValue({
      features: {},
      customerService: { corpId: "ww123", url: "" },
    });
    const s = useConfigStore();
    await s.load();

    expect(s.wxKfReady).toBe(false);
  });

  it("后端没发这一档时是空串而不是 undefined —— 页面里的判断少一种写法", async () => {
    bootstrapConfig.mockResolvedValue({ features: {} });
    const s = useConfigStore();
    await s.load();

    expect(s.customerService).toEqual({ corpId: "", url: "" });
    expect(s.wxKfReady).toBe(false);
  });

  it("拉配置失败时也不是 undefined —— 冷启动抖一下不该让这一行变成崩溃", async () => {
    bootstrapConfig.mockRejectedValue(new Error("network"));
    const s = useConfigStore();
    await s.load();

    expect(s.wxKfReady).toBe(false);
    expect(s.customerService).toEqual({ corpId: "", url: "" });
  });
});
