import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { STORAGE } from "@shared/utils/constants";

/**
 * 门店列表这份会话上下文：**什么时候算作数、拉不到时怎么办**。
 *
 * <p>两条规则都只在 App 上要命，而 H5 永远看不见它们坏 —— 刷一次页面就重来一遍。
 * App 的 `onLaunch` 只跑一次，进程能活好几天：**这份列表在整段会话里只拉一次**。
 * 于是那一次拉错/拉不到，就会一路错到下次杀进程。
 *
 * <p>症状伪装得很好：`stores` 里找不到当前门店 → `currentStore` 为 null
 * → `multiStore` 退回 false → 「我的」头部显示**主体名**（证照抬头）。
 * 主体名按定义不跟着切店变，所以它看着就是一条正常信息，只是永远停在切换之前。
 * 店主报上来的原话是「切了门店，『我的』顶上还是之前那家」。
 */

const mStoreList = vi.fn();
const mBizScope = vi.fn();
const mProfile = vi.fn();

vi.mock("@/api", () => ({
  api: {
    mStoreList: (...a: unknown[]) => mStoreList(...a),
    mBizScope: (...a: unknown[]) => mBizScope(...a),
    mProfile: (...a: unknown[]) => mProfile(...a),
    mMyStores: () => Promise.resolve([]),
  },
}));

vi.mock("@shared/ports/push", () => ({ getPushDevice: () => Promise.resolve(null) }));

/** uni 的同步存储：这几个 action 直接读写它，没有它模块加载就炸 */
const storage = new Map<string, unknown>();
vi.stubGlobal("uni", {
  getStorageSync: (k: string) => storage.get(k) ?? "",
  setStorageSync: (k: string, v: unknown) => void storage.set(k, v),
  removeStorageSync: (k: string) => void storage.delete(k),
});

const { useMerchantStore } = await import("@/stores/merchant");

function store(storeNo: string, name: string) {
  return { storeNo, name, status: "ACTIVE", isDefault: storeNo === "A" };
}

beforeEach(() => {
  setActivePinia(createPinia());
  storage.clear();
  mStoreList.mockReset();
  mBizScope.mockReset().mockResolvedValue({ perms: ["*"] });
  mProfile.mockReset().mockResolvedValue({ merchantNo: "M1", name: "阿明果蔬合作社", status: "ACTIVE" });
});

describe("门店列表 · 这份列表还作数吗", () => {
  it("★★★ 当前门店不在列表里 = 列表属于上一张证照，要重拉（否则「我的」顶部永远停在切换前）", async () => {
    const m = useMerchantStore();
    m.token = "t";
    // 跨证照切店后的真实中间态：storeNo 已经是 B 家的，stores 还是 A 家那份，
    // 而 switchStore 里那次补救重拉失败了（网络抖一下，`.catch` 吞掉，没人重试）。
    m.stores = [store("A1", "张记粮油"), store("A2", "古荡店")] as never;
    m.storeNo = "B1";
    storage.set(STORAGE.storeNo, "B1");
    mStoreList.mockResolvedValue([store("B1", "张记水果 · 文一路店")]);

    await m.ensureStores();

    // 只判「发了请求」不够：它可能拉回来又被兜底逻辑丢掉。要判到那一格读的值上。
    expect(mStoreList).toHaveBeenCalledTimes(1);
    expect(m.storeNo).toBe("B1");
    expect(m.currentStore?.name).toBe("张记水果 · 文一路店");
  });

  it("列表作数时不多发请求 —— 每个页面的 onShow 都在调它", async () => {
    const m = useMerchantStore();
    m.token = "t";
    m.stores = [store("A1", "张记粮油"), store("A2", "古荡店")] as never;
    m.storeNo = "A2";

    await m.ensureStores();
    await m.ensureStores();

    expect(mStoreList).not.toHaveBeenCalled();
  });
});

describe("门店列表 · 拉不到时", () => {
  it("★★★ 拉不到不等于一家店都没有 —— 不许把当前门店清掉", async () => {
    const m = useMerchantStore();
    m.token = "t";
    m.stores = [store("A1", "张记粮油"), store("A2", "古荡店")] as never;
    m.storeNo = "A2";
    storage.set(STORAGE.storeNo, "A2");
    mStoreList.mockRejectedValue(new Error("network"));

    await m.loadStores();

    // 兜成 [] 的话下面会一路走到 switchStore("")，连本地那个号一起删 ——
    // App 冷启那一秒网络没就绪，人就被静默送回默认店，而他记得自己切过。
    expect(m.storeNo).toBe("A2");
    expect(storage.get(STORAGE.storeNo)).toBe("A2");
    expect(m.stores).toHaveLength(2);
  });

  it("真的一家店都没有（新注册还没建店）仍然清空 —— 这条路不能跟着一起改掉", async () => {
    const m = useMerchantStore();
    m.token = "t";
    m.storeNo = "A2";
    storage.set(STORAGE.storeNo, "A2");
    mStoreList.mockResolvedValue([]);

    await m.loadStores();

    expect(m.storeNo).toBe("");
    expect(storage.has(STORAGE.storeNo)).toBe(false);
  });
});
