// 登录态 + 用户资料。token 走 uni storage（请求层直接读），资料走 pinia persist。
import { defineStore } from "pinia";
import { api } from "@/api";
import { STORAGE } from "@shared/utils/constants";
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
      return resp.user;
    },

    async loadProfile() {
      if (!this.token) return null;
      this.user = await api.profile();
      return this.user;
    },

    logout() {
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
