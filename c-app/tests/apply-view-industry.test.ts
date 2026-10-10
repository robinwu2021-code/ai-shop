/**
 * 查看入驻意向那一屏：「店铺类型」要显示**名字**，不是行业码。
 *
 * <p>这条是 2026-09-29 在 H5 mock 上截图时撞见的真缺陷：直接点进查看态时，
 * 主数据只有「打开报名表」那条路会去拉，于是这一格摆给店主的是 `FRESH` ——
 * 界面不报错、也不空，只是把程序标识符当成了店铺类型。
 *
 * <p><b>为什么不写成「源码里包含 ensureMasterData」</b>：那种断言改个函数名就假绿，
 * 而这个缺陷恰恰是「函数在、但那条路没调它」。所以挂起来看渲染出的字。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";

// 页面里读了 __BUILD_VERSION__（Vite 的 define），vitest 下它不存在 —— 不补就挂不起来
vi.stubGlobal("__BUILD_VERSION__", "test");
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import type { MasterData, MerchantApplyStatus } from "@shared/types";

const myMerchantApply = vi.fn();
const masterData = vi.fn();

vi.mock("@/api", () => ({
  api: {
    myMerchantApply: () => myMerchantApply(),
    masterData: () => masterData(),
    // 登录之后 onShow 会调 loadProfile —— 替身缺它就抛 unhandled rejection：
    // 用例仍然「通过」，只在汇总行多一句 Errors 1（[[verify-on-real-path]] 那类）
    profile: vi.fn(async () => ({ userNo: "U1", nickname: "邻居小张", phone: "13800138000" })),
    myFission: vi.fn(async () => null),
    unreadMessages: vi.fn(async () => 0),
    pointAccount: vi.fn(async () => ({ balance: 0 })),
    visitedStores: vi.fn(async () => []),
  },
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (q: Record<string, string>) => unknown) => cb({}),
  onShow: (cb: () => unknown) => cb(),
  onHide: vi.fn(),
  onPullDownRefresh: vi.fn(),
  onReachBottom: vi.fn(),
  onShareAppMessage: vi.fn(),
  onShareTimeline: vi.fn(),
  onPageScroll: vi.fn(),
}));

import MePage from "@/pages/me/index.vue";
import { useUserStore } from "@/stores/user";

const APPLY = {
  applyNo: "MA-1",
  name: "张记粮油",
  subject: "INDIVIDUAL",
  status: "PENDING",
  contactName: "",
  contactPhone: "13800138000",
  category: "粮油",
  desc: "",
  industry: "FRESH",
  createdAt: 0,
} as unknown as MerchantApplyStatus;

const MASTER = {
  industries: [{ industry: "FRESH", name: "生鲜果蔬", microAllowed: true }],
  intentIndustries: [
    { industry: "FRESH", name: "生鲜果蔬", open: true },
    { industry: "CATERING", name: "餐饮", open: false },
  ],
  subjects: [],
  channels: [],
  serviceScopes: [],
} as unknown as MasterData;

async function render() {
  /*
   * **必须先登录**：onShow 里那句 `if (user.isLogin)` 决定去不去拉意向单，
   * 不登录的话 applyStatus 恒空 —— 点开店走的是**新建报名表**那条路，
   * 而那条路自己也会拉主数据。第一版用例就是这么假绿的：
   * 把查看态那句 ensureMasterData 删掉，它照样通过。
   */
  useUserStore().token = "ctk_test";

  const w = mount(MePage, {
    global: {
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-sheet": { template: "<div><slot /></div>" },
        "sh-icon": true,
        "phone-gate": true,
        "biz-app-download": true,
      },
      mocks: { $t: (k: string) => k },
    },
  });
  for (let i = 0; i < 12; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
  return w;
}

describe("查看入驻意向", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    myMerchantApply.mockReset().mockResolvedValue(APPLY);
    masterData.mockReset().mockResolvedValue(MASTER);
  });

  it("★★★ 点进查看态就把主数据备齐 —— 否则店铺类型摆的是行业码", async () => {
    const w = await render();
    const entry = w.find(".open-shop");
    expect(entry.exists(), "找不到开店入口，这条用例没测到该测的东西").toBe(true);
    await entry.trigger("tap");
    for (let i = 0; i < 12; i++) {
      await Promise.resolve();
      await w.vm.$nextTick();
    }

    /*
     * **先确认真的在查看态**：报名表那一屏也会渲染出「生鲜果蔬」（它是行业选项之一），
     * 所以不锚住这一条的话，走错屏同样能让下面两句通过。
     */
    const text = w.text();
    expect(text, "没进查看态 —— 这条用例测的不是那一屏").toContain("merchant.intentIndustry");

    expect(masterData, "查看态没去拉主数据").toHaveBeenCalled();
    expect(text).toContain("生鲜果蔬");
    // 反向也要钉：只断言「有名字」的话，两个都渲染出来同样能过
    expect(text).not.toContain("FRESH");
  });
});
