// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import "./component-setup";
const m = vi.hoisted(() => ({ scope: vi.fn(), can: vi.fn(), messages: vi.fn(), pending: vi.fn(), show: undefined as undefined | (() => unknown) }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => ({ ensureScope: m.scope, can: m.can }) }));
vi.mock("@/api", () => ({ api: { mMessageList: m.messages, mAfterSaleWorklist: m.pending } }));
vi.mock("@dcloudio/uni-app", () => ({ onShow: (cb: () => unknown) => { m.show = cb; } }));
import Page from "@/pages/messages/index.vue";
beforeEach(() => {
  vi.clearAllMocks();
  m.scope.mockResolvedValue(undefined);
  m.can.mockReturnValue(true);
  m.messages.mockResolvedValue([{ messageNo: "MSG1", title: "待发货消息", content: "原订单", type: "TRADE", actionPending: true, read: false, at: 1 }]);
  m.pending.mockResolvedValue({ records: [], total: 1 });
});
async function render() {
  const w = mount(Page, { global: { mocks: { $t: (k: string) => k }, stubs: { "sh-scaffold": { template: "<div><slot/></div>" } } } });
  await m.show?.();
  for (let i = 0; i < 10; i++) await Promise.resolve();
  return w;
}
it("深链权限加载完成后查询售后待办", async () => {
  let ready = false;
  m.can.mockImplementation(() => ready);
  m.scope.mockImplementation(async () => { ready = true; });
  const w = await render();
  expect(m.pending).toHaveBeenCalledOnce();
  expect(w.text()).toContain("待发货消息");
  w.unmount();
});
it("售后待办查询失败不隐藏已经加载的消息", async () => {
  m.pending.mockRejectedValue(new Error("pending unavailable"));
  const w = await render();
  expect(w.text()).toContain("待发货消息");
  w.unmount();
});
it("无售后权限保留消息且不查询售后", async () => {
  m.can.mockReturnValue(false);
  const w = await render();
  expect(m.pending).not.toHaveBeenCalled();
  expect(w.text()).toContain("待发货消息");
  w.unmount();
});
