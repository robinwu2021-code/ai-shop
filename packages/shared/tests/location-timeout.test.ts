import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * **定位回调一个都不来时，界面不能永远转圈。**
 *
 * `uni.getLocation` 的 success / fail 可能**都不触发**：小程序端涉隐私的接口
 * 在「用户隐私保护指引」未配置或未授权时会挂起，地理位置接口权限没在后台
 * 申请时也有同样表现。
 *
 * 症状不是「定位失败」——那反而是好处理的。症状是**选社区页永远停在
 * 「定位中…」**：页面在，底下什么都没有，用户看到的是「一打开就跳过来、
 * 还点不了」。而这一页正是新用户的第一屏。
 *
 * 这条守的是：**任何依赖系统回调的加载态都必须有上界。**
 */
describe("定位：回调不来也要收场", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    delete (globalThis as { uni?: unknown }).uni;
    vi.resetModules();
  });

  async function load() {
    return (await import("../src/ports/location")).getLocationDetailed;
  }

  it("★★ 两个回调都不来 → 超时后按「拿不到」收场，不是永远挂着", async () => {
    // 只收参数、永不回调 —— 正是真机上挂起时的样子
    (globalThis as { uni?: unknown }).uni = { getLocation: () => {} };
    const getLocationDetailed = await load();

    const p = getLocationDetailed();
    let settled = false;
    void p.then(() => (settled = true));

    await vi.advanceTimersByTimeAsync(7000);
    expect(settled, "7 秒时还不该收场 —— 真机冷启动定位偶尔要五六秒").toBe(false);

    await vi.advanceTimersByTimeAsync(2000);
    await expect(p).resolves.toEqual({ ok: false, reason: "unavailable", detail: "timeout" });
  });

  it("★ 正常成功不受影响，且不会被超时二次 resolve", async () => {
    (globalThis as { uni?: unknown }).uni = {
      getLocation: (o: { success: (r: unknown) => void }) =>
        o.success({ latitude: 30.28, longitude: 120.1 }),
    };
    const getLocationDetailed = await load();
    const r = await getLocationDetailed();
    expect(r).toEqual({ ok: true, coords: { lat: 30.28, lng: 120.1 } });

    // 超时的定时器即便还在，也不许改写已经定下的结果
    await vi.advanceTimersByTimeAsync(20000);
    expect(r).toEqual({ ok: true, coords: { lat: 30.28, lng: 120.1 } });
  });

  it("★★★ 精确定位不可用 → 退到模糊定位，并**标出它是模糊的**", async () => {
    (globalThis as { uni?: unknown }).uni = {
      // getLocation 未获批时就是这个形状：不是 denied，是接口不可用
      getLocation: (o: { fail: (e: unknown) => void }) =>
        o.fail({ errMsg: "getLocation:fail api need to be declared" }),
      getFuzzyLocation: (o: { success: (r: unknown) => void }) =>
        o.success({ latitude: 30.2, longitude: 120.1 }),
    };
    const getLocationDetailed = await load();
    const r = await getLocationDetailed();
    expect(r).toEqual({ ok: true, coords: { lat: 30.2, lng: 120.1 }, fuzzy: true });
  });

  it("★★★ 用户明确拒绝时**不**再问一次模糊的 —— 那是同一个表态", async () => {
    let askedFuzzy = false;
    (globalThis as { uni?: unknown }).uni = {
      getLocation: (o: { fail: (e: unknown) => void }) =>
        o.fail({ errMsg: "getLocation:fail auth deny" }),
      getFuzzyLocation: () => (askedFuzzy = true),
    };
    const getLocationDetailed = await load();
    const r = await getLocationDetailed();
    expect(askedFuzzy, "拒绝之后再弹一次是骚扰").toBe(false);
    expect(r).toMatchObject({ ok: false, reason: "denied" });
  });

  it("★ 明确失败照旧区分 denied / unavailable —— 超时不该把这个能力吃掉", async () => {
    (globalThis as { uni?: unknown }).uni = {
      getLocation: (o: { fail: (e: unknown) => void }) =>
        o.fail({ errMsg: "getLocation:fail auth deny" }),
    };
    const getLocationDetailed = await load();
    await expect(getLocationDetailed()).resolves.toMatchObject({ ok: false, reason: "denied" });
  });
});
