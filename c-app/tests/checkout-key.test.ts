import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/*
 * 结算页的幂等键 —— **按行为测，不按源码测**。
 *
 * 2026-09-18 真机：绑完手机号自动提交一单、跳支付页，他退回来又点一次购买，
 * 13 秒内两张一模一样的待付款单。后端同键回放早就在（24 小时），缺的是端上给同一个键。
 * 这组用例钉的是「什么时候必须同一个键、什么时候必须换新键」—— 两个方向都错不得：
 * 该同不同 = 重复下单；该换不换 = 把他送去付一张已付 / 已关的单。
 */
vi.mock("@/api", () => {
  let n = 0;
  return { idempotencyKey: () => `k${++n}` };
});

const store = new Map<string, unknown>();
beforeEach(() => {
  store.clear();
  vi.stubGlobal("uni", {
    getStorageSync: (k: string) => (store.has(k) ? store.get(k) : ""),
    setStorageSync: (k: string, v: unknown) => void store.set(k, v),
    removeStorageSync: (k: string) => void store.delete(k),
  });
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-09-19T10:00:00+08:00"));
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.resetModules();
});

async function load() {
  return import("@/shared/checkout-key");
}

describe("结算页幂等键", () => {
  it("★★★ 同一单内容再提交一次（退出去再进来）拿到的是同一个键 —— 这就是那次重复下单", async () => {
    const { checkoutKey } = await load();
    const a = checkoutKey('{"items":[1]}');
    const b = checkoutKey('{"items":[1]}');
    expect(b).toBe(a);
  });

  it("★★★ 内容一变就是新单：换了货 / 地址 / 收法，必须换键", async () => {
    const { checkoutKey } = await load();
    const a = checkoutKey('{"items":[1]}');
    const b = checkoutKey('{"items":[2]}');
    expect(b).not.toBe(a);
  });

  it("★★★ 付成功之后清掉 —— 紧接着再买一份一模一样的，不能被回放成那张已付的单", async () => {
    const { checkoutKey, clearCheckoutKey } = await load();
    const a = checkoutKey('{"items":[1]}');
    clearCheckoutKey();
    expect(checkoutKey('{"items":[1]}')).not.toBe(a);
  });

  it("★★★ 键不活过那张单的付款截止 —— 单已关还回放，就是让他去付一张付不了的单", async () => {
    const { checkoutKey, checkoutKeyBoundTo } = await load();
    const fp = '{"items":[1]}';
    const a = checkoutKey(fp);
    const deadline = Date.now() + 15 * 60 * 1000;
    checkoutKeyBoundTo(fp, deadline);

    vi.setSystemTime(deadline - 2 * 60 * 1000); // 截止前 2 分钟：还能付，回放
    expect(checkoutKey(fp)).toBe(a);

    vi.setSystemTime(deadline - 30 * 1000); // 截止前 30 秒：跳过去也来不及，换新键
    expect(checkoutKey(fp)).not.toBe(a);
  });

  it("★★ 截止按单上的时间算，不被暂定的 10 分钟截短（商家把付款窗口调长时）", async () => {
    const { checkoutKey, checkoutKeyBoundTo } = await load();
    const fp = '{"items":[1]}';
    const a = checkoutKey(fp);
    checkoutKeyBoundTo(fp, Date.now() + 30 * 60 * 1000);
    vi.setSystemTime(Date.now() + 20 * 60 * 1000); // 第 20 分钟：超过暂定寿命，但单还能付
    expect(checkoutKey(fp)).toBe(a);
  });

  it("★★ 存储不可用时退回「每次新键」，不让下单失败", async () => {
    vi.stubGlobal("uni", {
      getStorageSync: () => { throw new Error("quota"); },
      setStorageSync: () => { throw new Error("quota"); },
      removeStorageSync: () => { throw new Error("quota"); },
    });
    const { checkoutKey, clearCheckoutKey } = await load();
    expect(() => checkoutKey("x")).not.toThrow();
    expect(() => clearCheckoutKey()).not.toThrow();
  });
});
