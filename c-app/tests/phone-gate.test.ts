/**
 * 「留个手机号」弹层。
 *
 * <p>这一段有两个容易做错的地方，都在这里钉住：
 * <ol>
 *   <li><b>一键授权不是随时可用的</b>（要小程序已认证 + 非个人主体，还按次计费）。
 *       所以能力由后端说了算，不可用时必须回落到验证码 —— 而不是给一个点了没反应的按钮。</li>
 *   <li><b>手机号被别人占了时不能说「已被占用」</b>。用户会以为有人抢了他的号，
 *       而多半是他自己以前在 H5 注册过。</li>
 * </ol>
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

const phoneCapable = vi.fn();
const bindPhone = vi.fn();
const bindPhoneByWx = vi.fn();
const sendOtp = vi.fn();

vi.mock("@/api", () => ({
  api: {
    phoneCapable: () => phoneCapable(),
    bindPhone: (...a: unknown[]) => bindPhone(...a),
    bindPhoneByWx: (...a: unknown[]) => bindPhoneByWx(...a),
    sendOtp: (...a: unknown[]) => sendOtp(...a),
    profile: () => Promise.resolve({ phone: "13800138000" }),
  },
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));

import PhoneGate from "@/components/phone-gate.vue";

async function render() {
  const w = mount(PhoneGate, {
    props: { show: true },
    global: { mocks: { $t: (k: string) => k } },
  });
  for (let i = 0; i < 8; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
  return w;
}

describe("留手机号弹层", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    phoneCapable.mockResolvedValue({ capable: false });
  });

  it("★★ 一键不可用 → 显示验证码表单，不显示一键按钮", async () => {
    const w = await render();
    expect(w.text()).toContain("phoneGate.sendCode");
    expect(w.text()).not.toContain("phoneGate.oneTap");
  });

  it("★★ 一键可用 → 显示一键按钮（并保留「用验证码」的入口）", async () => {
    phoneCapable.mockResolvedValue({ capable: true });
    const w = await render();
    expect(w.text()).toContain("phoneGate.oneTap");
    expect(w.text()).toContain("phoneGate.useCode");
  });

  it("★★ 问不到能力时按不可用处理 —— 宁可多一步，也不要给一个点不动的按钮", async () => {
    phoneCapable.mockRejectedValue(new Error("network"));
    const w = await render();
    expect(w.text()).toContain("phoneGate.sendCode");
    expect(w.text()).not.toContain("phoneGate.oneTap");
  });

  it("★★★ 号码属于别人 → 说「已注册过」，**不说「已被占用」**", async () => {
    bindPhone.mockRejectedValue(Object.assign(new Error("conflict"), { code: 10409 }));
    const w = await render();

    await w.findAll("input")[0].setValue("13500135003");
    await w.findAll("input")[1].setValue("123456");
    await w.find("button").trigger("tap");
    for (let i = 0; i < 8; i++) {
      await Promise.resolve();
      await w.vm.$nextTick();
    }

    expect(w.text()).toContain("phoneGate.conflict");
    expect(w.emitted("done")).toBeUndefined();
  });

  it("★★ 绑定成功 → 抛 done，让调用方继续原来的动作", async () => {
    bindPhone.mockResolvedValue({ phone: "13500135001" });
    const w = await render();

    await w.findAll("input")[0].setValue("13500135001");
    await w.findAll("input")[1].setValue("123456");
    await w.find("button").trigger("tap");
    for (let i = 0; i < 8; i++) {
      await Promise.resolve();
      await w.vm.$nextTick();
    }

    expect(bindPhone).toHaveBeenCalledWith("13500135001", "123456");
    expect(w.emitted("done")).toBeTruthy();
  });
});
