import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { uniMock } from "./setup";

const mocks = vi.hoisted(() => ({ track: vi.fn(), open: vi.fn() }));
vi.mock("@/api", () => ({
  api: { track: mocks.track, trackMine: mocks.track, trackMiniLink: async () => null },
  ApiError: class extends Error {},
}));
vi.mock("@/stores/user", () => ({ useUserStore: () => ({ isLogin: true, isPhoneLogin: true }) }));
vi.mock("@/ports/wx-logistics", () => ({ openWxWaybillTracking: mocks.open }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (q: object) => void) => queueMicrotask(() => cb({ t: "recipient-ticket" })),
  onShareAppMessage: vi.fn(),
}));
import TrackPage from "@/pages/track/index.vue";

const parcel = () => ({
  subOrderNo: "SUB1", status: "SHIPPED", fulfillment: "EXPRESS", items: [],
  buyerNickname: "下单人小王",
  trace: { status: "TRANSIT", nodes: [], displayMode: "wx-plugin", displayToken: "waybill-current" },
});
async function render() {
  const w = mount(TrackPage, { global: {
    stubs: { "sh-scaffold": { template: "<div><slot /></div>" } },
    mocks: { $t: (key: string) => key },
  } });
  await flushPromises();
  return w;
}

describe("recipient tracking page", () => {
  beforeEach(() => { vi.clearAllMocks(); mocks.track.mockResolvedValue(parcel()); mocks.open.mockReturnValue(true); });
  it.each([true, false])("keeps a working home exit, parcel available=%s", async available => {
    if (!available) mocks.track.mockResolvedValue(null);
    const w = await render();
    const home = w.findAll(".sh-btn").find(button => button.text() === "track.goHome");
    expect(home).toBeDefined();
    await home!.trigger("tap");
    expect(uniMock.switchTab).toHaveBeenCalledWith({ url: "/pages/home/index" });
  });
  it("opens the plugin even when no local trace nodes have arrived", async () => {
    const w = await render();
    const open = w.findAll(".sh-btn").find(button => button.text() === "trace.openWx");
    expect(open).toBeDefined();
    await open!.trigger("tap");
    expect(mocks.open).toHaveBeenCalledWith("waybill-current");
  });
  it("shows plugin failure feedback", async () => {
    mocks.open.mockReturnValue(false);
    const w = await render();
    await w.findAll(".sh-btn").find(button => button.text() === "trace.openWx")!.trigger("tap");
    expect(uniMock.showToast).toHaveBeenCalledWith({ title: "trace.wxUnavailable", icon: "none" });
  });
  it("shows the order placer returned by the authorized view", async () => {
    const w = await render();
    expect(w.text()).toContain("track.orderedBy");
    expect(w.text()).toContain("下单人小王");
  });
});
