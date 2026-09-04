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
import { metersBetweenE6 } from "@shared/utils/geo";

/** 候选要多近才值得问。再远就不是「你好像在那儿」，是他在逛街 */
const SUGGEST_NEAR_M = 1000;
/** 当前生效位置要多远才算「确实换地方了」。两者都在附近时问一句纯属打扰 */
const SUGGEST_FAR_M = 3000;

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
        /*
         * **服务端说的生效位置，端上的归属要跟上。**
         *
         * 生效位置存在服务端（换设备、重装之后还是同一个上下文），
         * 而商品池挂在本地的 community 归属上。只读不同步的话，
         * 会出现「顶栏写着公司、商品还是家那边的」—— 两个都对，合起来是错的。
         * 实测撞到过：从别处切了位置，回到首页顶栏变了、商品没变。
         */
        if (this.active) await this.syncCommunityFromActive();
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
    /**
     * 定位到了别处 —— **要不要建议他切过去。** 返回该建议的地址，没有就是 null。
     *
     * <p>这是「手动多选」被否掉之后的替代方案（决策记录 D3）：多选的真实驱动力是
     * 「切换太麻烦」，所以把切换做便宜，而不是让他同时挂着两个地方看一锅混合的货。
     *
     * <p><b>三条门槛，每一条都在挡一种误报：</b>
     * <ul>
     *   <li><b>坐标必须是精确的</b> —— 模糊定位误差约 5 公里，拿它比距离，
     *       在城里几乎每次都会「发现」你在别处；</li>
     *   <li><b>候选要足够近</b>（1 公里内）—— 否则「你好像在公司」会在他去逛街时冒出来；</li>
     *   <li><b>当前生效位置要足够远</b>（3 公里外，或压根没坐标）——
     *       两者都在附近时切不切都一样，问一句纯属打扰。</li>
     * </ul>
     *
     * <p><b>只返回建议，绝不自动切。</b>自动切会让人在完全没察觉的情况下
     * 看到另一个地方的货 —— 那比麻烦糟得多。
     */
    suggestSwitch(at: { lat: number; lng: number; fuzzy?: boolean }): Address | null {
      if (at.fuzzy) return null;
      const latE6 = Math.round(at.lat * 1e6);
      const lngE6 = Math.round(at.lng * 1e6);
      const near = (a: Address) =>
        a.latE6 == null || a.lngE6 == null
          ? Number.POSITIVE_INFINITY
          : metersBetweenE6(a.latE6, a.lngE6, latE6, lngE6);

      let best: Address | null = null;
      let bestM = Number.POSITIVE_INFINITY;
      for (const a of this.list) {
        const m = near(a);
        if (m < bestM) {
          best = a;
          bestM = m;
        }
      }
      if (!best || bestM > SUGGEST_NEAR_M) return null;
      if (this.active && best.addressId === this.active.addressId) return null;
      // 当前那个也在附近 = 切不切都一样，别打扰
      if (this.active && near(this.active) < SUGGEST_FAR_M) return null;
      return best;
    },

    /**
     * 切到某个位置，并把商品池跟着换过去。
     *
     * <p><b>返回「归属有没有跟着换」</b> —— 没坐标的地址（微信导入、粘贴识别、
     * 存量手填）推不出聚落，此时 `syncCommunityFromActive` 什么都不做，这是对的
     * （清掉的话用户会发现「换了个地址，商品全没了」）。但**不能一声不吭**：
     * 他看到顶栏变了而商品没变，无从判断是坏了还是设计如此。
     * 调用方据此说一句「这个地址没有定位点，商品仍按 XX 显示」。
     */
    async switchTo(addressId: string) {
      const addr = await api.switchActiveAddress(addressId);
      this.active = addr;
      const rebound = await this.syncCommunityFromActive();
      return { addr, rebound };
    },

    /**
     * 由生效位置的坐标解析出归属社区并绑定。
     *
     * <p><b>没有坐标就什么都不做</b>，而不是清掉现有归属：
     * 从微信地址簿导入的地址不带经纬度（`chooseAddress` 只给文字），
     * 那种位置照样是个有效的收货地址，只是推不出社区。
     * 清掉的话，用户会发现自己「换了个地址，商品全没了」。
     */
    /** @returns 归属有没有真的换过去。false = 这条地址没坐标，推不出聚落 */
    async syncCommunityFromActive(): Promise<boolean> {
      const a = this.active;
      if (!a || a.latE6 == null || a.lngE6 == null) return false;
      const community = useCommunityStore();
      const list = await community
        .loadNearby(a.latE6 / 1e6, a.lngE6 / 1e6)
        .catch(() => [] as never[]);
      /*
       * **「我在哪」由后端定，端上不再取第一条。**
       *
       * 判据是「层级优先于距离」（站在楼门口时，隔壁小区的中心可能比本楼中心更近）——
       * 那是业务规则。此前端上取 `list[0]`，等于把规则抄进了端，
       * 而 c-app / b-app / 将来的 H5 会各写一份，它们迟早不一样。
       *
       * 解析失败（新城区、模糊坐标）时回落到最近的那一条 —— 与改造前一致，
       * 不因为多了一次请求就让人看不到货。
       */
      const ctx = await api.resolveLocation(a.latE6, a.lngE6).catch(() => null);
      const c = list.find((x) => x.communityNo === ctx?.innermostNo) ?? list[0];
      const p = c?.pickups?.[0];
      if (c && p) {
        await community.bind(c, p);
        return true;
      }
      // 有坐标但一个聚落都没落进（新城区）—— 也算没换成，调用方同样要说一句
      return false;
    },
  },
});
