/**
 * 「选社区自提点」页的**状态机**。
 *
 * <p>这一页在 2026-08-21 一天里返工了三次，三次都是状态问题、三次都只能靠人在真机上发现：
 * <ol>
 *   <li>没有空态与失败态 —— 请求一挂就是一片白（新用户的第一屏）</li>
 *   <li>定位结果拿到了却没传给后端 —— 「附近」名不副实</li>
 *   <li>区域列表挂在 v-else-if 链的空态**之后** —— 那段 DOM 一次都没渲染过</li>
 * </ol>
 *
 * <p>第三条尤其说明问题：数据是好的、接口是通的、状态也算对了，
 * 只是那个分支永远进不去。源码扫描式的守卫（packages/shared/tests）拦不住这种事，
 * 类型检查也拦不住 —— 只有把页面真的挂起来看它渲染出什么才拦得住。
 *
 * <p>断言用 **i18n key** 而不是中文文案：文案随时会改，而「显示的是哪一种状态」不该随文案漂移。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { uniMock } from "./setup";

const nearbyCommunities = vi.fn();
const allCommunities = vi.fn();
const openRegions = vi.fn();

vi.mock("@/api", () => ({
  api: {
    nearbyCommunities: (...a: unknown[]) => nearbyCommunities(...a),
    allCommunities: (...a: unknown[]) => allCommunities(...a),
    openRegions: (...a: unknown[]) => openRegions(...a),
  },
}));

const getLocationMock = vi.fn();
vi.mock("@shared/ports/location", () => ({
  getLocation: () => getLocationMock(),
}));

import CommunityPage from "@/pages/community/index.vue";

const COMMUNITY = {
  communityNo: "C0001",
  name: "阳光花园",
  address: "杭州市西湖区文一西路 100 号",
  distance: 300,
  pickups: [
    { pickupNo: "PP0001", name: "老张粮油店（自提点）", address: "东门旁", distance: 300 },
  ],
};
const REGION = {
  regionCode: "330106",
  name: "西湖区",
  cityCode: "3301",
  cityName: "杭州市",
  communityCount: 2,
};

/** 挂载并等异步 load() 跑完。onLoad 在 setup.ts 里被改成直接执行 */
async function render() {
  const w = mount(CommunityPage, {
    global: {
      stubs: { "sh-scaffold": { template: "<div><slot /></div>" } },
      mocks: { $t: (k: string) => k },
    },
  });
  // load() 里有两到三个 await，逐个 flush
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
  return w;
}

vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));

describe("选社区自提点页", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    getLocationMock.mockResolvedValue({ lat: 30.28, lng: 120.1 });
    nearbyCommunities.mockResolvedValue([]);
    allCommunities.mockResolvedValue([]);
    openRegions.mockResolvedValue([]);
  });

  it("★ 附近有 → 出社区列表，不去问区域", async () => {
    nearbyCommunities.mockResolvedValue([COMMUNITY]);
    const w = await render();

    expect(w.text()).toContain("阳光花园");
    expect(openRegions).not.toHaveBeenCalled();
    expect(w.text()).not.toContain("community.pickRegion");
  });

  it("★★★ 附近没有但有区域 → 出**区域列表**（这条正是 0.1.6 漏掉的）", async () => {
    nearbyCommunities.mockResolvedValue([]);
    openRegions.mockResolvedValue([REGION]);
    const w = await render();

    expect(w.text()).toContain("community.pickRegion");
    expect(w.text()).toContain("西湖区");
    expect(w.text()).toContain("杭州市");
    // 空态不该同时出现 —— 上一版正是空态抢先命中，把区域列表整段挡住了
    expect(w.text()).not.toContain("community.empty");
  });

  it("★★ 附近没有、区域也空 → 才是空态", async () => {
    nearbyCommunities.mockResolvedValue([]);
    openRegions.mockResolvedValue([]);
    allCommunities.mockResolvedValue([]);
    const w = await render();

    expect(w.text()).toContain("community.empty");
  });

  it("★★ 请求失败 → 失败态 + 重试，而不是一片白", async () => {
    nearbyCommunities.mockRejectedValue(new Error("network down"));
    const w = await render();

    expect(w.text()).toContain("community.failed");
    expect(w.text()).toContain("community.retry");
    expect(w.text()).not.toContain("community.empty");
  });

  it("★★ 选中一个区 → 只列该区社区，且能退回重选", async () => {
    nearbyCommunities.mockResolvedValue([]);
    openRegions.mockResolvedValue([REGION]);
    allCommunities.mockResolvedValue([COMMUNITY]);
    const w = await render();

    await w.find(".rg__item").trigger("tap");
    for (let i = 0; i < 4; i++) {
      await Promise.resolve();
      await w.vm.$nextTick();
    }

    expect(allCommunities).toHaveBeenCalledWith("330106");
    expect(w.text()).toContain("阳光花园");
    expect(w.text()).toContain("community.changeRegion");

    await w.find(".rg__back").trigger("tap");
    await w.vm.$nextTick();
    expect(w.text()).toContain("community.pickRegion");
  });

  it("★★★ 定位结果必须传给后端 —— 不传的话「附近」名不副实", async () => {
    getLocationMock.mockResolvedValue({ lat: 23.129, lng: 113.264 });
    nearbyCommunities.mockResolvedValue([COMMUNITY]);
    await render();

    expect(nearbyCommunities).toHaveBeenCalledWith(23.129, 113.264);
  });

  it("★ 定位被拒 → 照样加载（后端不过滤），不卡在定位中", async () => {
    getLocationMock.mockResolvedValue(null);
    nearbyCommunities.mockResolvedValue([COMMUNITY]);
    const w = await render();

    expect(nearbyCommunities).toHaveBeenCalledWith(undefined, undefined);
    expect(w.text()).toContain("阳光花园");
    expect(w.text()).not.toContain("community.locating");
    expect(uniMock.showToast).not.toHaveBeenCalled();
  });
});
