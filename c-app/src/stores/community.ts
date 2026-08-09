// 社区归属：决定看到哪套商品池、价格与履约时效，也是佣金归属依据。
// 一个用户在一个时点只归属一个社区自提点。
import { defineStore } from "pinia";
import { api } from "@/api";
import { useUserStore } from "./user";
import { STORAGE } from "@shared/utils/constants";
import type { Community, Pickup } from "@shared/types";

export const useCommunityStore = defineStore("community", {
  state: () => ({
    list: [] as Community[],
    community: null as Community | null,
    pickup: null as Pickup | null,
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

    async loadNearby() {
      this.list = await api.nearbyCommunities();
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
