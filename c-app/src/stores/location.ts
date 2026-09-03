// 「当前生效位置」：用户存了多个位置（家 / 公司 / …），任一时刻只按一个看货。
//
// **它不是收货地址。** 同一条记录常常两者都是，但回答的是不同问题：
//   生效位置 = 现在按哪儿看货（浏览上下文）
//   默认地址 = 下单预填哪个收货人（长期偏好）
// 给父母下单时两者不一样。合成一个的后果是改了一个另一个跟着变。
import { defineStore } from "pinia";
import { api } from "@/api";
import { useCommunityStore } from "./community";
import type { Address } from "@shared/types";

export const useLocationStore = defineStore("location", {
  state: () => ({
    /** 当前生效位置。**null 是常态**，不是错误：新用户一个都没有 */
    active: null as Address | null,
    list: [] as Address[],
    loading: false,
  }),

  getters: {
    /** 顶栏显示的短名：优先用标签（家/公司），否则用详细地址 */
    label: (s) => (s.active ? s.active.tag || s.active.detail || s.active.region : ""),
    has: (s) => !!s.active,
  },

  actions: {
    async load() {
      this.loading = true;
      try {
        const [active, list] = await Promise.all([
          api.activeAddress().catch(() => null),
          api.addressList().catch(() => [] as Address[]),
        ]);
        this.active = active;
        this.list = list;
      } finally {
        this.loading = false;
      }
    },

    /**
     * 切到某个位置，并**把商品池跟着换过去**。
     *
     * <p><b>这一跳是「第一步不动主轴」的全部关键。</b>
     * 后端的商品池仍挂在 `communityNo` 上（商家可见性、佣金归属、
     * 配送范围判定都串在那根轴上），所以这里用地址的坐标去查
     * 「哪个已开通社区覆盖它」，再沿用现有的绑定链路。
     *
     * <p>将来第二步会去掉这一跳、由后端按坐标实时算 ——
     * 到那时**端上这个函数之外一行都不用改**，这正是分两步的理由。
     */
    async switchTo(addressId: string) {
      const addr = await api.switchActiveAddress(addressId);
      this.active = addr;
      await this.syncCommunityFromActive();
      return addr;
    },

    /**
     * 由生效位置的坐标解析出归属社区并绑定。
     *
     * <p><b>没有坐标就什么都不做</b>，而不是清掉现有归属：
     * 从微信地址簿导入的地址不带经纬度（`chooseAddress` 只给文字），
     * 那种位置照样是个有效的收货地址，只是推不出社区。
     * 清掉的话，用户会发现自己「换了个地址，商品全没了」。
     */
    async syncCommunityFromActive() {
      const a = this.active;
      if (!a || a.latE6 == null || a.lngE6 == null) return;
      const community = useCommunityStore();
      const list = await community
        .loadNearby(a.latE6 / 1e6, a.lngE6 / 1e6)
        .catch(() => [] as never[]);
      const c = list[0];
      const p = c?.pickups?.[0];
      if (c && p) await community.bind(c, p);
    },
  },
});
