import { beforeEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { uniMock } from "./setup";
const favoriteStores = vi.fn();
vi.mock("@/api", () => ({ api: { favoriteGoods: async () => ({ records: [], total: 0 }), favoriteStores: () => favoriteStores() } }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@dcloudio/uni-app", () => ({ onShow: (cb: () => unknown) => cb(), onReachBottom: vi.fn() }));
import { useUserStore } from "@/stores/user";
import Favorites from "@/pages/favorites/index.vue";
beforeEach(() => { setActivePinia(createPinia()); useUserStore().token = "phone-session"; useUserStore().user = { phone: "13800138000" } as never; vi.clearAllMocks(); });
it("同一主体两家门店保留两行，点击按门店号进入门户", async () => {
 favoriteStores.mockResolvedValue([{ merchantNo: "M1", storeNo: "ST1", name: "南山店" }, { merchantNo: "M1", storeNo: "ST2", name: "福田店" }]);
 const w = mount(Favorites, { global: { mocks: { $t: (k: string) => k }, stubs: {
  "sh-scaffold": { template: "<div><slot /></div>" },
  "sh-tabs": { template: '<button @click="$emit(\'change\', \'store\')">门店</button>' },
  "biz-shop-row": { props: ["merchant"], template: '<button class="store" @click="$emit(\'tap\')">{{ merchant.name }}</button>' },
 } } });
 await flushPromises(); await w.find("button").trigger("click");
 const rows = w.findAll(".store"); expect(rows.map(r => r.text())).toEqual(["南山店", "福田店"]);
 await rows[1].trigger("click"); expect(uniMock.navigateTo).toHaveBeenCalledWith({ url: "/pages/store/index?no=ST2" });
});
