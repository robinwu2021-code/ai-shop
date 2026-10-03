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
