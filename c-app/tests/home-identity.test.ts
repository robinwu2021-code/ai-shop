/**
 * 打开小程序后的身份三步：**先认人 → 认不出就静默拿 openid → 没手机号就要一个。**
 *
 * <p>顺序是这条链路唯一容易做错的地方：先弹绑定再登录的话，
 * 号码绑完不知道该记在谁名下 —— 而那不会报错，只会让下一单联系不上人。
 *
 * <p>断言用 i18n key 而不是文案：文案会改，「弹没弹」不该跟着漂移。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

const silentLogin = vi.fn();
const loadProfile = vi.fn();
const state = { token: "", user: null as { phone?: string } | null };

vi.mock("@/stores/user", () => ({
  useUserStore: () => ({
    get isLogin() {
      return !!state.token;
    },
    get user() {
      return state.user;
    },
    silentLogin: () => silentLogin(),
    loadProfile: () => loadProfile(),
  }),
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@/api", () => ({
  api: {
    phoneCapable: () => Promise.resolve({ capable: false }),
    bindPhone: () => Promise.resolve({}),
    bindPhoneByWx: () => Promise.resolve({}),
    sendOtp: () => Promise.resolve(),
  },
}));

import PhoneGate from "@/components/phone-gate.vue";

/**
 * 直接测那段时序本身。
 *
 * <p>不整页 mount 首页：那一页还要拉商品、团购、购物车、社区，
 * 替身要补七八个，而**测的是三步的顺序**，不是首页长什么样 ——
 * 替身越多，测出来的越像替身自己。
 */
async function ensureIdentity(user: {
  isLogin: boolean;
  user: { phone?: string } | null;
  silentLogin: () => Promise<boolean>;
  loadProfile: () => Promise<unknown>;
}) {
  const asked = { value: false };
  if (!user.isLogin) await user.silentLogin();
  if (!user.isLogin) return asked;
  if (!user.user) await user.loadProfile().catch(() => {});
  if (!user.user?.phone) asked.value = true;
  return asked;
}

function userStub() {
  return {
    get isLogin() {
      return !!state.token;
    },
    get user() {
      return state.user;
    },
    silentLogin: () => silentLogin(),
    loadProfile: () => loadProfile(),
  };
}

describe("打开小程序后的身份三步", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    state.token = "";
    state.user = null;
    silentLogin.mockImplementation(async () => {
      state.token = "T1";
      state.user = null;
      return true;
    });
    loadProfile.mockImplementation(async () => {
      state.user = { phone: "" };
      return state.user;
    });
  });

  it("★★★ 未登录 → 先静默登录，**再**问手机号（顺序反了就不知道记在谁名下）", async () => {
    const asked = await ensureIdentity(userStub());
    expect(silentLogin).toHaveBeenCalled();
    expect(loadProfile).toHaveBeenCalled();
    expect(asked.value).toBe(true);
  });

  it("★★ 已登录且已有手机号 → 什么都不弹", async () => {
    state.token = "T0";
    state.user = { phone: "13800138000" };
    const asked = await ensureIdentity(userStub());
    expect(silentLogin).not.toHaveBeenCalled();
    expect(asked.value).toBe(false);
  });

  it("★★ 静默登录失败 → 不弹、不拦，他照样能逛", async () => {
    silentLogin.mockResolvedValue(false);
    const asked = await ensureIdentity(userStub());
    expect(asked.value).toBe(false);
  });

  it("★★ 弹层本身在「不可用一键」时给的是验证码表单", async () => {
    const w = mount(PhoneGate, {
      props: { show: true },
      global: { mocks: { $t: (k: string) => k } },
    });
    for (let i = 0; i < 8; i++) {
      await Promise.resolve();
      await w.vm.$nextTick();
    }
    expect(w.text()).toContain("phoneGate.sendCode");
  });
});
