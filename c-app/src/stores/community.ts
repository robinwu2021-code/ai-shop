// 社区归属：决定看到哪套商品池、价格与履约时效，也是佣金归属依据。
// 一个用户在一个时点只归属一个社区自提点。
import { defineStore } from "pinia";
import { getLocation } from "@shared/ports/location";
import { api } from "@/api";
import { useUserStore } from "./user";
import { STORAGE } from "@shared/utils/constants";
import type { Community, Pickup } from "@shared/types";

export const useCommunityStore = defineStore("community", {
  state: () => ({
    list: [] as Community[],
    community: null as Community | null,
    pickup: null as Pickup | null,
    /**
     * 附近到底有没有自提点。`null` = 本次会话还没探过。
     * 见 {@link probeNearby} —— 它决定未绑定的用户要不要被推去选社区页。
     */
    nearbyProbe: null as boolean | null,
  }),

  getters: {
    bound: (s) => !!s.pickup,
    /** 承接自提点的商家名（ADR-005：承接方是入驻商家，不再是团长） */
    hostName: (s) => s.pickup?.hostName ?? "",
  },

  actions: {
    restore() {
      // persist 插件已恢复 community/pickup，这里仅作占位以统一 App.vue 调用心智
    },

    /**
     * @param lat 定位纬度。**必须传下去** —— 早先这里不带参数，
     *   而 `getLocation()` 的结果在页面里被丢弃了，于是后端永远走「无坐标」分支：
     *   距离恒 0、排序退化成库序，「附近」两个字名不副实。
     */
    async loadNearby(lat?: number, lng?: number) {
      this.list = await api.nearbyCommunities(lat, lng);
      return this.list;
    },

    /**
     * 探一次「附近到底有没有自提点」。**给「要不要把用户推去选社区页」用的。**
     *
     * <p>未绑定的用户此前一律被强制推到选社区页 —— 而所在区域还没开通时，
     * 他被推过去只会看到一句「这一带还没有自提点」，然后每次回首页再被推一次。
     * 那一页对他没有任何用处，却是他绕不过去的第一屏。
     *
     * 结果**按会话缓存**：这条判断挂在首页 onShow 上，不缓存就是每次切回首页都定位 + 请求一次。
     * 网络失败**不缓存也不当成「没有」** —— 那会把一次网络抖动变成「这个区域没开通」，
     * 而用户下次回来还是这个结论。
     */
    async probeNearby(): Promise<boolean> {
      if (this.nearbyProbe !== null) return this.nearbyProbe;
      try {
        const at = await getLocation();
        const list = await api.nearbyCommunities(at?.lat, at?.lng);
        this.nearbyProbe = list.length > 0;
        // 探到的结果直接留给选社区页，省它再请求一次
        if (this.nearbyProbe) this.list = list;
        return this.nearbyProbe;
      } catch {
        return false; // 探不出来就不推 —— 宁可让他自己去点，也不要把他关进一个空页面
      }
    },

    /** 全部已开通社区 —— 附近为空 / 未定位时的手动选择路径 */
    async loadAll() {
      this.list = await api.allCommunities();
      return this.list;
    },

    /**
     * 绑定社区自提点。
     *
     * <p><b>未登录也要能选</b>：选社区是「逛」的前置条件，不是账号操作 ——
     * 商品、门店、团购在后端都是游客可访问的，唯独归属这一步要 token。
     * 早先 mock 下这是纯本地写，看不出问题；接上真后端后，新用户第一屏
     * 就是选社区，点下去直接 401，而页面上只有一个未捕获错误，什么也不会发生。
     *
     * <p>所以：未登录只存本地，登录时再补同步（见 {@link syncBinding}）。
     */
    async bind(community: Community, pickup: Pickup) {
      const user = useUserStore();
      if (user.isLogin) {
        await api.bindCommunity(community.communityNo, pickup.pickupNo);
      }
      this.community = community;
      this.pickup = pickup;
    },

    /**
     * 把本地归属同步到后端。登录成功后调用 —— 游客期间选的社区不能白选，
     * 否则登录反而把他刚做的选择丢了。
     */
    async syncBinding() {
      if (!this.community || !this.pickup) return;
      try {
        await api.bindCommunity(this.community.communityNo, this.pickup.pickupNo);
      } catch {
        // 同步失败不该挡住登录：归属在本地是完整的，下次绑定或切社区还会再试
      }
    },

    /**
     * 切语言后重新本地化已绑定的社区/自提点。
     * 归属是持久化的，里面存的是**绑定当时那门语言**的文案；后端按 Accept-Language 返回，
     * 所以换语言必须重拉一次，否则页面上会出现中英阿混排。
     */
    async refreshLocalized() {
      if (!this.pickup) return;
      const boundCommunityNo = this.community?.communityNo;
      const boundPickupNo = this.pickup.pickupNo;
      try {
        await this.loadNearby();
      } catch {
        return; // 启动时网络未就绪等情况：保留旧快照，不阻塞，下次切语言还会再校正
      }
      const c = this.list.find((x) => x.communityNo === boundCommunityNo);
      const p = c?.pickups.find((x) => x.pickupNo === boundPickupNo);
      if (c && p) {
        this.community = c;
        this.pickup = p;
      }
    },

    clear() {
      this.community = null;
      this.pickup = null;
    },
  },

  persist: {
    key: STORAGE.community,
    pick: ["community", "pickup"],
  },
});
