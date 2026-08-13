// 登录态 + 用户资料。token 走 uni storage（请求层直接读），资料走 pinia persist。
import { defineStore } from "pinia";
import { api } from "@/api";
import { STORAGE } from "@shared/utils/constants";
import { useCommunityStore } from "./community";
import type { LoginReq, User } from "@shared/types";

export const useUserStore = defineStore("user", {
  state: () => ({
    token: "" as string,
    user: null as User | null,
  }),

  getters: {
    isLogin: (s) => !!s.token,

  },

  actions: {
    restore() {
      this.token = (uni.getStorageSync(STORAGE.token) as string) || "";
    },

    async login(req: LoginReq) {
      const resp = await api.login(req);
      this.token = resp.token;
      this.user = resp.user;
      uni.setStorageSync(STORAGE.token, resp.token);
      // 游客期间选的社区要补同步 —— 否则登录这一步反而把他刚做的选择丢了
      await useCommunityStore().syncBinding();
      return resp.user;
    },

    async loadProfile() {
      if (!this.token) return null;
      this.user = await api.profile();
      return this.user;
    },

    /**
     * 登出。**先调后端作废会话，再清本地**——顺序不能反。
     *
     * 此前这里只清本地，服务端的令牌一直有效到自然过期：
     * 用户以为退出了，实际拿到过这个令牌的人还能继续用。
     *
     * 接口失败也要清本地：让用户「退不出去」是更糟的体验，
     * 而且失败多半是网络问题，令牌会随过期自然失效。
     */
    async logout() {
      try {
        await api.logout();
      } catch {
        // 吞掉：本地必须清干净，理由见上
      }
      this.clearSession();
    },

    /**
     * 只清本地，**不调后端**。
     *
     * 令牌已经失效时走的就是这一条（App.vue 里的 401 处理）——
     * 那时再调一次 `/logout` 只会再收一个 401，而那个 401 又会触发同一段处理，
     * 转起来就下不来了。正常登出仍然要先调后端作废会话，见 {@link logout}。
     */
    clearSession() {
      this.token = "";
      this.user = null;
      uni.removeStorageSync(STORAGE.token);
    },
  },

  persist: {
    key: STORAGE.user,
    pick: ["user"],
  },
});
