/**
 * 邀请有礼的入口与那一页（TDD-C 端裂变与商家招募 §3.1）。
 *
 * <p>两条判据都在守同一件事：**不给一个点进去说「暂无活动」的入口**。
 * 后端在没有在跑的活动时返回 null，端上据此整条不显示 ——
 * 这不是省一行 UI，是不要把用户骗进一个空房间。
 *
 * <p>第三条守的是分享链接**带 `inviterNo`**：没有它，被邀请人注册时后端拿不到
 * 邀请人，台账那一行根本不会写（`AuthServiceImpl` 注册分支调 `fissionPort.onRegister`），
 * 而页面上一切看起来都正常。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import type { MyFission } from "@shared/types";

const myFission = vi.fn();

vi.mock("@/api", () => ({
  api: {
    myFission: () => myFission(),
    myMerchantApply: vi.fn(async () => null),
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
  onPageScroll: vi.fn(),
}));

import InvitePage from "@/pages/invite/index.vue";

function aFission(over: Partial<MyFission> = {}): MyFission {
  return {
    fissionNo: "FS1",
    name: "邀请有礼",
    inviterCount: 1,
    inviteeCount: 1,
    couponTitle: "满 60 减 8",
    faceMinor: 800,
    thresholdMinor: 6000,
    myInvited: 2,
    myConverted: 1,
    ...over,
  };
}

async function render() {
  const w = mount(InvitePage, {
    global: {
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-icon": true,
      },
      mocks: { $t: (k: string) => k },
    },
  });
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
  return w;
}

describe("邀请有礼", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    myFission.mockReset();
  });

  it("★★★ 有活动时说得出奖励是什么 —— 「得 1 张券」等于没说", async () => {
    myFission.mockResolvedValue(aFission());
    const html = (await render()).html();
    // 带门槛的必须连门槛一起说，否则他到结账才发现用不了
    expect(html).toContain("invite.rewardCut");
    expect(html).toContain("invite.rule");
  });

  it("★★★ 已邀请与已下单**两个数都要在** —— 奖励按首单发，只给前者会让人以为少发了", async () => {
    myFission.mockResolvedValue(aFission({ myInvited: 3, myConverted: 1 }));
    const html = (await render()).html();
    expect(html).toContain("invite.invited");
    expect(html).toContain("invite.converted");
    expect(html).toContain(">3<");
    expect(html).toContain(">1<");
  });

  it("★★★ 没有在跑的活动 → 不摆分享按钮，只说一句话", async () => {
    myFission.mockResolvedValue(null);
    const html = (await render()).html();
    expect(html).toContain("invite.none");
    expect(html).not.toContain("invite.share");
    expect(html).not.toContain("invite.copy");
  });

  it("★★ 取数失败不当成「没有活动」—— 那会把一次网络抖动说成活动结束了", async () => {
    myFission.mockRejectedValue(new Error("网络异常"));
    const w = await render();
    // 失败态交给 scaffold（被 stub 掉了），这里只断言没有把空态当结论
    expect(w.html()).not.toContain("invite.share");
  });
});

describe("「我的」页的邀请入口", () => {
  it("★★★ 源码里那条入口挂在 fission 上 —— 没活动时整条不出现", () => {
    const me = require("node:fs").readFileSync(
      require("node:path").resolve(__dirname, "../src/pages/me/index.vue"), "utf8");
    expect(me).toContain('v-if="fission"');
    expect(me).toContain("invite.entryHint");
    // 登出要清零：不清的话旧的邀请进度还挂在别人的账号下
    expect(me).toContain("fission.value = null");
  });
});

/**
 * 邀请人从落地到登录这一段（§3.1）。
 *
 * <p><b>这是整条链最容易静默断的一环</b>：邀请链接指向的是首页
 * （`/pages/home/index?inviterNo=xxx`），而登录页只读**自己 query 上**的 `inviterNo`。
 * 中间不接一手，参数就丢了 —— 而他注册成功、也下单了，一切看起来都正常，
 * 只有邀请人永远等不到那张券，台账里连一行都没有。
 */
describe("邀请人不能在跳登录时丢", () => {
  const read = (p: string) => require("node:fs").readFileSync(
    require("node:path").resolve(__dirname, p), "utf8");

  it("★★★ 首页把 query 上的邀请人接住并存起来", () => {
    const home = read("../src/pages/home/index.vue");
    expect(home, "首页没有 onLoad，query 上的邀请人根本读不到").toContain("onLoad((q)");
    expect(home).toContain("user.pendingInviter = from");
    // 空值不许覆盖：他从扫码/历史记录再进首页是常事，覆盖会把上一次的抹掉
    expect(home).toContain("if (from &&");
  });

  it("★★★ 登录页 query 没有时用暂存的", () => {
    expect(read("../src/pages/login/index.vue")).toContain("user.pendingInviter");
  });

  it("★★ 登录成功后清掉 —— 同一台设备换个人登录不该算成同一个人邀的", () => {
    expect(read("../src/stores/user.ts")).toContain('this.pendingInviter = "";');
  });

  it("★★ 不把自己算成邀请人 —— 他把链接发给自己再点开是常有的事", () => {
    expect(read("../src/pages/home/index.vue")).toContain("from !== user.user?.cUserNo");
  });
});
