import { beforeEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
const goodsList = vi.fn();
const storeNearby = vi.fn();
vi.mock("@/api", () => ({ api: { goodsList: (...args: unknown[]) => goodsList(...args), storeNearby: (...args: unknown[]) => storeNearby(...args) } }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
import Search from "@/pages/search/index.vue";
function render() {
 return mount(Search, { global: { mocks: { $t: (key: string) => key }, stubs: {
  "sh-scaffold": { template: "<div><slot /></div>" }, "sh-tabs": true, "biz-cart-fab": true,
  "biz-goods-card": { props: ["goods"], template: '<div class="goods">{{ goods.title }}</div>' },
  "biz-store-row": { props: ["store"], template: '<div class="store">{{ store.storeName }}</div>' },
 } } });
}
async function search(w: ReturnType<typeof render>, q: string) {
 await w.find("input").setValue(q); await w.find(".searchbar__btn").trigger("tap"); await flushPromises();
}
beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks(); storeNearby.mockResolvedValue({ records: [] }); goodsList.mockResolvedValue({ records: [] }); });
it("搜索失败后不展示旧结果，并提供重试", async () => {
 goodsList.mockResolvedValueOnce({ records: [{ goodsNo: "G1", title: "旧苹果" }] }).mockRejectedValueOnce(new Error("offline"));
 const w=render(); await search(w,"苹果"); expect(w.text()).toContain("旧苹果");
 await search(w,"橙子"); expect(w.text()).not.toContain("旧苹果"); expect(w.text()).toContain("common.loadFailed"); expect(w.text()).toContain("common.retry");
 goodsList.mockResolvedValueOnce({ records: [{ goodsNo: "G2", title: "新橙子" }] });
 await w.findComponent({ name: "sh-empty" }).vm.$emit("retry"); await flushPromises(); expect(w.text()).toContain("新橙子");
});
it("两个域均为空时只显示一份空态", async () => {
 const w=render(); await search(w,"空结果"); expect(w.findAllComponents({ name: "sh-empty" })).toHaveLength(1); expect(w.text()).toContain("search.nothing");
});
it("加载中不闪空态，迟到的旧关键词结果不能覆盖新搜索", async () => {
 let resolveOld!: (value: unknown) => void;
 goodsList.mockImplementationOnce(() => new Promise(resolve => { resolveOld=resolve; })).mockResolvedValueOnce({ records: [{ goodsNo: "G2", title: "新橙子" }] });
 const w=render(); await search(w,"苹果"); expect(w.text()).not.toContain("search.nothing");
 await search(w,"橙子"); resolveOld({ records: [{ goodsNo: "G1", title: "旧苹果" }] }); await flushPromises(); expect(w.text()).toContain("新橙子"); expect(w.text()).not.toContain("旧苹果");
});
