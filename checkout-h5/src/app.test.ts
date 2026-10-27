import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, render } from "./app";

type Reply = { status: number; body: unknown };
let routes: Record<string, Reply>;
let calls: { method: string; url: string; body?: string }[];

function ok(data: unknown): Reply {
  return { status: 200, body: { code: 0, message: "OK", data, requestId: "r1" } };
}

const SESSION = {
  payOrderNo: "PYO1", payeeName: "邻里鲜果", storeName: "中山店", amountMinor: 1990, currency: "CNY",
  currencyScale: 2, market: "CN", status: "OPEN", payMethods: ["TEST_PAY"], expireAt: Date.now() + 600_000,
};

beforeEach(() => {
  calls = [];
  routes = {};
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    calls.push({ method, url, body: init?.body as string | undefined });
    const r = routes[`${method} ${url}`] ?? { status: 404, body: { code: 10404, message: "", data: null } };
    return new Response(JSON.stringify(r.body), { status: r.status, headers: { "Content-Type": "application/json" } });
  }));
  Object.defineProperty(window.navigator, "languages", { value: ["zh-CN"], configurable: true });
  document.body.innerHTML = '<main id="app"></main>';
  sessionStorage.clear();
});

afterEach(() => vi.unstubAllGlobals());

async function open(path: string) {
  window.history.replaceState(null, "", path);
  const app = createApp(document.getElementById("app")!);
  await render(app);
  return app;
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe("收银台页面", () => {
  it("★★★ 付款页：收款方在金额上方；服务端字符串只当文本（不注入）", async () => {
    routes["GET /checkout/v1/sessions/tok"] = ok({ ...SESSION, payeeName: '<img src=x onerror="alert(1)">' });
    await open("/c/tok");
    expect(document.querySelector("[data-view=pay]")).not.toBeNull();
    expect(document.querySelector("[data-testid=payee]")?.textContent).toContain("<img");
    expect(document.querySelector("img")).toBeNull();
    expect(document.querySelector("[data-testid=amount]")?.textContent).toContain("19.90");
    expect(document.querySelector("button.primary")?.textContent).toContain("19.90");
  });

  it("★★★ 令牌失效（410）与不存在（404）是同一屏", async () => {
    routes["GET /checkout/v1/sessions/old"] = { status: 410, body: { code: 70093, message: "x", data: null } };
    await open("/c/old");
    expect(document.querySelector("[data-view=expired]")).not.toBeNull();
    await open("/c/none");
    expect(document.querySelector("[data-view=expired]")).not.toBeNull();
  });

  it("★★★ 小数位给不出：不显示金额、不给付款按钮", async () => {
    routes["GET /checkout/v1/sessions/tok"] = ok({ ...SESSION, currencyScale: null });
    await open("/c/tok");
    expect(document.querySelector("[data-testid=amount]")).toBeNull();
    expect(document.querySelector("button.primary")).toBeNull();
  });

  it("★★ 阿拉伯语：浏览器语言对不上时按市场 AE 选 ar，整页从右到左", async () => {
    Object.defineProperty(window.navigator, "languages", { value: ["fr-FR"], configurable: true });
    routes["GET /checkout/v1/sessions/tok"] = ok({ ...SESSION, currency: "AED", market: "AE" });
    await open("/c/tok");
    expect(document.documentElement.dir).toBe("rtl");
    expect(document.documentElement.lang).toBe("ar");
  });

  it("★★★ 选方式付款 → 测试通道直接去结果页；结果只看结果接口", async () => {
    routes["GET /checkout/v1/sessions/tok"] = ok(SESSION);
    routes["POST /checkout/v1/sessions/tok/pay"] = ok({ payOrderNo: "PYO1", outTradeNo: "T1", payChannel: "TEST",
      payParams: { prepayId: "test_1" } });
    routes["GET /checkout/v1/sessions/tok/result"] = ok({ result: "SUCCEEDED" });
    await open("/c/tok");
    (document.querySelector("button.primary") as HTMLButtonElement).click();
    for (let i = 0; i < 10 && !document.querySelector("[data-view=result]"); i++) await flush();
    expect(window.location.pathname).toBe("/result/PYO1");
    expect(document.querySelector("[data-view=result]")?.getAttribute("data-state")).toBe("succeeded");
    expect(JSON.parse(calls.find((c) => c.method === "POST")!.body!)).toMatchObject({ payMethod: "TEST_PAY" });
  });

  it("★ ?lang= 压过浏览器语言", async () => {
    routes["GET /checkout/v1/sessions/tok"] = ok(SESSION);
    await open("/c/tok?lang=ar");
    expect(document.documentElement.dir).toBe("rtl");
  });

  it("★★ 渠道回跳页没有令牌：同一标签页里记过就能查；没记过只说回原页面看", async () => {
    await open("/return/PYO9");
    expect(window.location.pathname).toBe("/result/PYO9");
    expect(document.querySelector("[data-state=unknown]")).not.toBeNull();
    expect(calls).toHaveLength(0);
  });

  it("★★★ 静态码：超单笔上限前端先拦、不建单；合法金额按最小单位提交后进付款页", async () => {
    routes["GET /checkout/v1/codes/QR1"] = ok({ payeeName: "邻里鲜果", storeName: "中山店", kind: "STORE", tableRef: null,
      currency: "CNY", currencyScale: 2, market: "CN", singleLimitMinor: 5000 });
    routes["POST /checkout/v1/codes/QR1/orders"] = ok({ payOrderNo: "PYO2", token: "tok2", expireAt: Date.now() + 1 });
    routes["GET /checkout/v1/sessions/tok2"] = ok({ ...SESSION, payOrderNo: "PYO2", amountMinor: 1250 });
    await open("/q/QR1");
    const input = document.querySelector("[data-testid=amount-input]") as HTMLInputElement;
    input.value = "60";
    (document.querySelector("button.primary") as HTMLButtonElement).click();
    await flush();
    expect(calls.filter((c) => c.method === "POST")).toHaveLength(0);
    input.value = "12.5";
    (document.querySelector("button.primary") as HTMLButtonElement).click();
    for (let i = 0; i < 10 && window.location.pathname !== "/c/tok2"; i++) await flush();
    expect(JSON.parse(calls.find((c) => c.method === "POST")!.body!)).toEqual({ amountMinor: 1250 });
    expect(window.location.pathname).toBe("/c/tok2");
  });
});
