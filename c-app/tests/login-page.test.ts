/**
 * 登录页的按钮层级。
 *
 * <p>此前小程序上同时铺着**两个同等分量的主按钮**：「微信一键登录」与「登录 / 注册」，
 * 而它们做的是同一件事 —— 用户唯一能做的判断是「猜哪个更对」。
 *
 * <p>现在：有快捷方式时手机号那条收起成一行次要入口；
 * 没有快捷方式的端（H5 / App）照旧直接给表单 —— 那边收起来只是多一次点击。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

const methods = vi.fn();
vi.mock("@shared/ports/auth", () => ({ loginMethods: () => methods() }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@/api", () => ({ api: { sendOtp: vi.fn(), login: vi.fn() } }));

const WX = { id: "WX_MINI", labelKey: "login.byWxMini", primary: true, needsPhone: false, acquire: vi.fn() };
const OTP = { id: "PHONE_OTP", labelKey: "login.byPhone", primary: true, needsPhone: true, acquire: vi.fn() };

import LoginPage from "@/pages/login/index.vue";

async function render() {
  const w = mount(LoginPage, {
    global: {
      stubs: { "sh-scaffold": { template: "<div><slot /></div>" } },
      mocks: { $t: (k: string) => k },
    },
  });
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
  return w;
}

const navigateTo = vi.fn();
const switchTab = vi.fn();

describe("登录页", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    Object.assign(globalThis.uni as Record<string, unknown>, { navigateTo, switchTab });
  });

  it("★★★ 小程序：只有微信那一个主按钮，手机号收起成次要入口", async () => {
    methods.mockReturnValue([WX, OTP]);
    const w = await render();

    expect(w.text()).toContain("login.byWxMini");
    expect(w.text()).toContain("login.orPhone");
    // 两个主按钮并排是这条用例要防的事
    expect(w.text()).not.toContain("login.submit");
    expect(w.findAll("input")).toHaveLength(0);
  });

  it("★★ 点了「用手机号」才展开表单", async () => {
    methods.mockReturnValue([WX, OTP]);
    const w = await render();

    await w.find(".switch").trigger("tap");
    await w.vm.$nextTick();

    expect(w.findAll("input").length).toBeGreaterThan(0);
    expect(w.text()).toContain("login.submit");
  });

  it("★★★ 协议必须能点开 —— 收手机号与位置的小程序，这是提审必查项", async () => {
    methods.mockReturnValue([WX, OTP]);
    const w = await render();

    const links = w.findAll(".agree__link");
    expect(links).toHaveLength(2);
    expect(w.text()).toContain("legal.terms");
    expect(w.text()).toContain("legal.privacy");

    await links[1].trigger("tap");
    expect(navigateTo).toHaveBeenCalledWith(
      expect.objectContaining({ url: "/pages/legal/index?doc=privacy" }),
    );
  });

  it("★★ 有一条「先逛逛」的出路 —— 被 401 弹过来的人不该困在这一页", async () => {
    methods.mockReturnValue([WX, OTP]);
    const w = await render();

    await w.find(".browse").trigger("tap");
    expect(switchTab).toHaveBeenCalledWith(
      expect.objectContaining({ url: "/pages/home/index" }),
    );
  });

  it("★★ H5 / App（没有快捷方式）：直接给表单，不多一次点击", async () => {
    methods.mockReturnValue([OTP]);
    const w = await render();

    expect(w.findAll("input").length).toBeGreaterThan(0);
    expect(w.text()).toContain("login.submit");
    expect(w.text()).not.toContain("login.byWxMini");
  });
});
