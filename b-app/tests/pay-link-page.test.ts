// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { uniMock } from "./component-setup";
import { isTraditional, linkText, PAY_LINK_ZH_TW_KEYS } from "@/pages/pay-link/texts";
import zhCN from "@/i18n/locale/zh-CN";
const m = vi.hoisted(() => ({ create: vi.fn(), status: vi.fn(), prompt: vi.fn() }));
vi.mock("@/api", () => ({ api: { mPayLinkCreate: m.create, mPayLinkStatus: m.status } }));
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => ({ can: () => true }) }));
vi.mock("@ai-shop/ui/prompt", () => ({ prompt: m.prompt }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k, locale: { value: "zh-CN" } }) }));
import Page from "@/pages/pay-link/index.vue";
async function flush() { for (let i = 0; i < 25; i++) await Promise.resolve(); }
const mountPage = () => mount(Page, { global: { mocks: { $t: (k: string) => k },
  stubs: { "sh-scaffold": { template: "<div><slot/></div>" }, "sh-section": { template: "<div><slot/></div>" },
    "sh-go": { props: ["text"], template: "<div class='go' @click=\"$emit('tap')\">{{ text }}</div>" }, "biz-pay-qr": { props: ["link"], template: "<i class='qr'>{{ link }}</i>" } } } });

const clip = vi.fn();
Object.assign(uniMock as Record<string, unknown>, { setClipboardData: clip });
beforeEach(() => { vi.clearAllMocks(); });

describe("收款链接（动态码）", () => {
  it("选有效期生成固定金额链接：同一次生成重试用同一 requestNo；显示链接、二维码与失效时间", async () => {
    m.prompt.mockResolvedValueOnce("350");
    m.create.mockRejectedValueOnce(new Error("网络错误")).mockResolvedValueOnce({ requestNo: "x", payOrderNo: "PYO1", status: "CREATED",
      url: "https://twpay.example/h5/c/tok", expireAt: Date.now() + 7 * 86400_000 });
    const w = mountPage();
    await w.findAll(".go")[0]!.trigger("click"); await flush();
    await w.get("[data-minutes='10080']").trigger("tap");
    await w.get("[data-action=generate]").trigger("tap"); await flush();
    expect(w.text()).toContain("网络错误");
    await w.get("[data-action=generate]").trigger("tap"); await flush();
    const [first, second] = m.create.mock.calls.map((c) => c[0]);
    expect(first.requestNo).toBe(second.requestNo);
    expect(second).toMatchObject({ amountMinor: 35000, expireMinutes: 10080 });
    expect(w.get("[data-testid=link-url]").text()).toBe("https://twpay.example/h5/c/tok");
    expect(w.get(".qr").text()).toBe("https://twpay.example/h5/c/tok");
    expect(w.get("[data-testid=link-expiry]").text()).toMatch(/bizPay.link.expiresAt/);
    await w.get("[data-action=copy]").trigger("tap");
    expect(clip).toHaveBeenCalledWith(expect.objectContaining({ data: "https://twpay.example/h5/c/tok" }));
    m.status.mockResolvedValue({ requestNo: first.requestNo, payOrderNo: "PYO1", status: "PAID", url: null, expireAt: Date.now() + 1000 });
    await w.get("[data-action=refresh]").trigger("tap"); await flush();
    expect(m.status).toHaveBeenCalledWith(first.requestNo);
    expect(w.get("[data-testid=link-status]").text()).toBe("bizPay.link.st_PAID");
    w.unmount();
  });

  it("繁体只在本页、只在中文应用 + 繁体系统时启用，词条与简体键一致", () => {
    expect(isTraditional("zh-CN", "zh-Hant-TW")).toBe(true);
    expect(isTraditional("zh-CN", "zh_TW")).toBe(true);
    expect(isTraditional("zh-CN", "zh-HK")).toBe(true);
    expect(isTraditional("zh-CN", "zh-Hans-CN")).toBe(false);
    expect(isTraditional("en", "zh-TW")).toBe(false);
    expect(linkText("zh-CN", "zh-TW", "expiresAt", { time: "11/6 14:30" })).toBe("11/6 14:30 前有效");
    expect(linkText("zh-CN", "zh-CN", "title")).toBeNull();
    const simplifiedKeys = Object.keys((zhCN as { bizPay: { link: Record<string, string> } }).bizPay.link).sort();
    expect([...PAY_LINK_ZH_TW_KEYS].sort()).toEqual(simplifiedKeys);
  });
});
