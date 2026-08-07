// 社区归属：决定看到哪套商品池、价格与履约时效，也是佣金归属依据。
// 一个用户在一个时点只归属一个社区自提点。
import { defineStore } from "pinia";
import { api } from "@/api";
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

    async bind(community: Community, pickup: Pickup) {
      await api.bindCommunity(community.communityNo, pickup.pickupNo);
      this.community = community;
      this.pickup = pickup;
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
