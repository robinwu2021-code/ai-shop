// 商家登录态 + 资料。对应 C 端的 stores/user —— 但两端是不同的账号体系：
// 同一个人可以既是消费者（C 端 cUserNo）又是商家（B 端 merchantNo），互不影响。
import { defineStore } from "pinia";
import { api } from "@/api";
import { STORAGE } from "@shared/utils/constants";
import type { LoginReq, MerchantProfile } from "@shared/types";

export const useMerchantStore = defineStore("merchant", {
  state: () => ({
    token: "" as string,
    profile: null as MerchantProfile | null,
  }),

  getters: {
    isLogin: (s) => !!s.token,
    /** 已入驻且正常经营 —— 未通过审核不能上架、不能收款 */
    isActive: (s) => s.profile?.status === "ACTIVE",
    /** 是否承接自提点 → 决定工作台是否出现「履约台」入口（ADR-005） */
    isPickupPoint: (s) => !!s.profile?.isPickupPoint,
  },

  actions: {
    restore() {
      this.token = (uni.getStorageSync(STORAGE.token) as string) || "";
    },

    /**
     * mock 下的演示会话。**没有它，B 端第一次打开是一个空壳**：
     * 工作台「还没有开店」、订单/商品/核销全空，看着像整个端坏了 ——
     * 以前不需要，是因为开发机上留着历史登录态，换存储命名空间后每个新浏览器都会撞上。
     * 只在「mock + 没有 token」时生效，真实后端与已登录用户都不受影响。
     */
    async useDemoSession() {
      if (this.token) return;
      this.token = "demo-token";
      uni.setStorageSync(STORAGE.token, this.token);
      await this.loadProfile();
    },

    async login(req: LoginReq) {
      const resp = await api.mLogin(req);
      this.token = resp.token;
      this.profile = resp.merchant;
      uni.setStorageSync(STORAGE.token, resp.token);
      return resp.merchant;
    },

    async loadProfile() {
      if (!this.token) return null;
      this.profile = await api.mProfile();
      return this.profile;
    },

    logout() {
      this.token = "";
      this.profile = null;
      uni.removeStorageSync(STORAGE.token);
    },
  },

  persist: {
    // 走 STORAGE.user（带端前缀）而不是写死 —— 写死的 key 绕过命名空间，
    // 两端一旦同域就会互相覆盖登录态
    key: STORAGE.user,
    pick: ["profile"],
  },
});
