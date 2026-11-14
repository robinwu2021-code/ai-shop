import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
const mocks = vi.hoisted(() => ({ locate:vi.fn(),show:undefined as undefined|(()=>void),records: vi.fn(), capability: vi.fn(), report: vi.fn(), native: false, load: {} as Record<string, string> }));
vi.mock("@/api", () => ({ api: { storedValueLocateIssuer:mocks.locate,storedValueIssuerRecords:mocks.records,storedValueRecords: mocks.records, storedValueNotificationCapability: mocks.capability, subscribeReport: mocks.report } }));
vi.mock("@/stores/user", () => ({ useUserStore: () => ({ token: "session", silentLogin: async () => false }) }));
vi.mock("@shared/ports/share", () => ({ canNativeShare: () => mocks.native }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (fn: (query: Record<string, string>) => void) => fn(mocks.load),
  onShow: (fn: () => void) => {mocks.show=fn;fn();}, onReachBottom: vi.fn(), onUnload: vi.fn(),
}));
import { uniMock } from "./setup";
import RecordsPage from "@/pages/stored-value-records/index.vue";
const render = () => mount(RecordsPage, { global: {
  mocks: { $t: (key: string) => key },
  stubs: {
    "sh-scaffold": { template: "<div><slot /></div>" },
    "sh-empty": { props: { failed: Boolean, text: String, pending: Boolean }, template: '<div v-if="!pending">{{ failed ? "failed" : text }}<button @tap="$emit(\'retry\')">retry</button></div>' },
    "sh-sheet": { props: ["visible"], template: '<div v-if="visible" class="test-sheet"><slot /><slot name="foot" /></div>' },
  },
} });
async function tapText(w: ReturnType<typeof render>, text: string) {
  const target = w.findAll("button, text").find(node => node.text().replace(/\s*›$/, "") === text);
  expect(target, text).toBeTruthy(); await target!.trigger("tap"); await flushPromises();
}
beforeEach(() => {
  vi.clearAllMocks();mocks.locate.mockResolvedValue({issuerRef:"S1"}); mocks.native = false; mocks.report.mockResolvedValue(undefined); mocks.load = { storeNo: "S1", focusDocumentNo: "EU1" };
  mocks.capability.mockResolvedValue({ channel: "WXSUB", available: true, templateId: "real", reasonCode: null });
  mocks.records.mockImplementation(async (_store, query) => ({
    store: { storeNo: "S1", storeName: "My Store", businessTimeZone: "Asia/Shanghai" },
    filter: { type: query.type, startDate: query.startDate || null, endDate: query.endDate || null },
    records: [{ type: "REDEMPTION", title: "Facial", currency: "CNY", signedAmountMinor: -12800, occurredAt: "2026-10-21T06:30:00Z", documentNo: "EU1" }],
    nextCursor: null, hasMore: false,
  }));
});
describe("储值账单交互", () => {
  it("通知焦点高亮；切类型清除焦点；日期取消不应用，重置仅清草稿", async () => {
    const w = render(); await flushPromises();
    expect(w.find(".record--focus").exists()).toBe(true);
    expect(mocks.records).toHaveBeenLastCalledWith("S1", expect.objectContaining({ focusDocumentNo: "EU1", type: "ALL" }));
    await w.findComponent({ name: "sh-tabs" }).vm.$emit("change", "RECHARGE"); await flushPromises();
    expect(w.find(".record--focus").exists()).toBe(false);
    expect(mocks.records.mock.lastCall?.[1]).not.toHaveProperty("focusDocumentNo");
    await tapText(w, "storedValue.time");
    await w.findAll("picker")[0]!.trigger("change", { detail: { value: "2026-10-01" } });
    const count = mocks.records.mock.calls.length;
    await tapText(w, "storedValue.cancel"); expect(mocks.records).toHaveBeenCalledTimes(count);
    await tapText(w, "storedValue.time");
    expect(w.findAll("picker")[0]!.text()).toBe("storedValue.unlimited");
    await w.findAll("picker")[0]!.trigger("change", { detail: { value: "2026-10-02" } });
    await tapText(w, "storedValue.apply");
    expect(mocks.records).toHaveBeenLastCalledWith("S1", { type: "RECHARGE", startDate: "2026-10-02", size: 20 });
    await tapText(w, "storedValue.time"); await tapText(w, "storedValue.reset");
    expect(mocks.records.mock.lastCall?.[1].startDate).toBe("2026-10-02");
    await tapText(w, "storedValue.apply");
    expect(mocks.records.mock.lastCall?.[1]).toEqual({ type: "RECHARGE", size: 20 });
  });
  it("非法日期不请求；H5即使能力可用也不展示原生微信授权按钮", async () => {
    const w = render(); await flushPromises();
    await tapText(w, "storedValue.time");
    await w.findAll("picker")[0]!.trigger("change", { detail: { value: "2026-10-21" } });
    await w.findAll("picker")[1]!.trigger("change", { detail: { value: "2026-10-01" } });
    const count = mocks.records.mock.calls.length;
    await tapText(w, "storedValue.apply"); expect(mocks.records).toHaveBeenCalledTimes(count);
    expect(w.text()).toContain("storedValue.invalidDates");
    await tapText(w, "storedValue.cancel"); await tapText(w, "storedValue.notifications");
    expect(w.text()).toContain("storedValue.notificationOtherPlatform");
    expect(w.findAll("button").some(button => button.text() === "storedValue.subscribe")).toBe(false);
  });
  it("小程序由点击同步发起真实授权，只上报返回的逐模板结果", async () => {
    mocks.native = true;
    const request = vi.fn((options: { success: (result: Record<string, string>) => void }) => options.success({ real: "accept" }));
    Object.assign(uniMock, { requestSubscribeMessage: request });
    const w = render(); await flushPromises();
    await tapText(w, "storedValue.notifications");
    expect(request).not.toHaveBeenCalled();
    await tapText(w, "storedValue.subscribe");
    expect(request).toHaveBeenCalledWith(expect.objectContaining({ tmplIds: ["real"] }));
    expect(mocks.report).toHaveBeenCalledWith(["real"], true);
    expect(uniMock.showToast).toHaveBeenCalledWith({ title: "storedValue.subscribeAccepted", icon: "none" });
  });
  it("模板不可用不展示授权，能力失败提供重试", async () => {
    mocks.native = true; mocks.capability.mockRejectedValue(new Error("offline"));
    const w = render(); await flushPromises();
    await tapText(w, "storedValue.notifications");
    expect(w.text()).toContain("failed");
    mocks.capability.mockResolvedValue({ channel: "WXSUB", available: false, templateId: null, reasonCode: "NOT_CONFIGURED" });
    await tapText(w, "retry");
    expect(w.text()).toContain("storedValue.notificationUnavailable");
    expect(w.findAll("button").some(button => button.text() === "storedValue.subscribe")).toBe(false);
  });
  it("404使用不可访问提示并提供返回门店入口", async () => {
    mocks.records.mockRejectedValueOnce({ code: 10404 });
    const w = render(); await flushPromises();
    expect(w.text()).toContain("storedValue.unavailable");
    expect(w.text()).toContain("storedValue.backStores");
    expect(w.text()).not.toContain("storedValue.emptyRecords");
  });
});

it('renews the durable notification locator when returning after the issuer reference expired',async()=>{
 mocks.locate.mockResolvedValueOnce({issuerRef:'FIRST'}).mockResolvedValue({issuerRef:'RENEWED'});
 const w=render();await flushPromises();expect(mocks.records.mock.calls.at(-1)?.[0]).toBe('FIRST');
 mocks.show!();await flushPromises();expect(mocks.locate).toHaveBeenCalledTimes(2);expect(mocks.records.mock.calls.at(-1)?.[0]).toBe('RENEWED');
 await w.findComponent({name:'sh-tabs'}).vm.$emit('change','RECHARGE');await flushPromises();
 expect(mocks.locate).toHaveBeenCalledTimes(3);expect(mocks.records.mock.calls.at(-1)?.[1]).not.toHaveProperty('focusDocumentNo');w.unmount();
});
