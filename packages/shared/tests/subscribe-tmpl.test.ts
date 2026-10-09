import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * 求订阅授权时，**没配的模板号必须先剔掉**。
 *
 * <p>微信对 `tmplIds` 是整批校验的：里面混一个不存在的号，整次调用直接 fail ——
 * 连同批里合法的那个也拿不到授权。症状是「用户从没见过授权弹窗、
 * 后端配额恒为 0」，而两端各自看都像是配好了。
 *
 * <p>这不是假想：本小程序的公共模板库里**没有退款那一类**，
 * 而支付成功页原本一次要两个（到货 + 退款）。
 */
describe("订阅授权：未配的模板号不许递给微信", () => {
  afterEach(() => {
    delete (globalThis as { uni?: unknown }).uni;
    vi.resetModules();
  });

  async function load() {
    return (await import("../src/ports/push")).requestSubscribe;
  }

  it("★★★ 混了占位号时，只递真实的那个", async () => {
    let got: string[] = [];
    (globalThis as { uni?: unknown }).uni = {
      requestSubscribeMessage: (o: { tmplIds: string[]; success: (r: unknown) => void }) => {
        got = o.tmplIds;
        o.success({ [o.tmplIds[0]!]: "accept" });
      },
    };
    const requestSubscribe = await load();
    const r = await requestSubscribe(["REAL_TPL_ID", "STUB_TPL_REFUNDED"]);
    expect(got, "占位号必须被剔掉，否则整批失败").toEqual(["REAL_TPL_ID"]);
    expect(r.accepted).toEqual(["REAL_TPL_ID"]);
  });

  it("★★ 一个真实的都没有 → 根本不调微信，直接返回空", async () => {
    let called = false;
    (globalThis as { uni?: unknown }).uni = {
      requestSubscribeMessage: () => (called = true),
    };
    const requestSubscribe = await load();
    const r = await requestSubscribe(["STUB_TPL_ORDER_ARRIVED", "STUB_TPL_REFUNDED"]);
    expect(called, "没有可用模板时不该弹窗打扰用户").toBe(false);
    expect(r).toEqual({ accepted: [], rejected: [] });
  });
});

/**
 * 一张单问哪几个模板（TDD-微信订阅消息优先 §2.2 / AC6 / AC7）。
 * 测的是**桩世界的占位号** —— 判的是「问了哪一类」，不是具体号。
 */
describe("下单 / 支付那一下问哪几个模板", () => {
  async function load() {
    return (await import("../src/ports/push")).orderSubscribeTmpls;
  }

  it("★★★ 微信支付的快递单一个都不问 —— 发货与物流动态微信自己推，再发是重复打扰", async () => {
    const pick = await load();
    expect(pick("EXPRESS", { offline: false, grouped: false })).toEqual([]);
  });

  it("★★★ 线下付款的快递单问揽收 / 派件 / 签收", async () => {
    const pick = await load();
    expect(pick("EXPRESS", { offline: true, grouped: false })).toEqual([
      "STUB_TPL_WAYBILL_PICKED_UP", "STUB_TPL_WAYBILL_DELIVERING", "STUB_TPL_WAYBILL_SIGNED",
    ]);
  });

  it("★★★ 拼团排第一、满 3 个截断 —— 没成团等于钱要退，比物流节点要紧", async () => {
    const pick = await load();
    const r = pick("EXPRESS", { offline: true, grouped: true });
    expect(r, "微信一次最多 3 个，第 4 个整批失败").toHaveLength(3);
    expect(r[0]).toBe("STUB_TPL_GROUP_RESULT");
  });

  it("★★ 商家配送问「开始配送」，自提问「到货」—— 两种都不分线上线下", async () => {
    const pick = await load();
    for (const offline of [true, false]) {
      expect(pick("MERCHANT_DELIVERY", { offline, grouped: false })).toEqual(["STUB_TPL_DELIVERY_START"]);
      expect(pick("STORE_PICKUP", { offline, grouped: false })).toEqual(["STUB_TPL_ORDER_ARRIVED"]);
    }
    expect(pick("NEIGHBOR_PICKUP", { offline: false, grouped: true }))
      .toEqual(["STUB_TPL_GROUP_RESULT", "STUB_TPL_ORDER_ARRIVED"]);
  });

  it("★★ 退款不再问 —— 微信支付单的退款到账微信支付自己推（AC6）", async () => {
    const pick = await load();
    for (const f of ["EXPRESS", "MERCHANT_DELIVERY", "STORE_PICKUP", "NEIGHBOR_PICKUP", "APPOINTMENT"]) {
      expect(pick(f, { offline: false, grouped: true })).not.toContain("STUB_TPL_REFUNDED");
    }
  });
});
