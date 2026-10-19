// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { reactive } from "vue";
import { flushPromises, mount } from "@vue/test-utils";
import "./component-setup";

const m = vi.hoisted(() => ({
  detail: vi.fn(), open: vi.fn(), fill: vi.fn(), post: vi.fn(), voidDoc: vi.fn(),
  store: {} as { multiStore: boolean; can: () => boolean; ensureStores: () => Promise<void> },
  load: undefined as ((q: { no: string }) => void | Promise<void>) | undefined,
}));
vi.mock("@/api", () => ({ api: {
  mCountDetail: m.detail, mCountOpen: m.open, mCountFill: m.fill, mCountPost: m.post, mCountVoid: m.voidDoc,
} }));
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => m.store }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock("@dcloudio/uni-app", () => ({ onLoad: (cb: (q: { no: string }) => void | Promise<void>) => {
  m.load = cb; void cb({ no: "CNT-READONLY" });
} }));
vi.mock("@ai-shop/ui/prompt", () => ({ confirm: vi.fn() }));
import Page from "@/pages/stock-check/index.vue";

describe("盘点截止只读详情", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    m.store = reactive({ multiStore: false, can: () => true, ensureStores: async () => {} });
    m.detail.mockResolvedValue({ countNo: "CNT-READONLY", status: "COUNTING",
      startedAt: "2026-10-19T14:00:00", lines: [{ itemId: "I-1", name: "梨", bookQty: 3, countedQty: null }] });
  });

  it("打开已有盘点单时说明账面截止口径，且仅读取单据", async () => {
    const wrapper = mount(Page, { global: { mocks: { $t: (key: string) => key },
      stubs: { "sh-scaffold": { template: "<main><slot/></main>" } } } });
    await flushPromises();

    expect(m.detail).toHaveBeenCalledExactlyOnceWith("CNT-READONLY");
    expect(wrapper.text()).toContain("stockCheck.cutoffHint");
    expect(wrapper.text()).toContain("stockCheck.lockedAt");
    expect(m.open).not.toHaveBeenCalled();
    expect(m.fill).not.toHaveBeenCalled();
    expect(m.post).not.toHaveBeenCalled();
    expect(m.voidDoc).not.toHaveBeenCalled();
    wrapper.unmount();
  });
});
