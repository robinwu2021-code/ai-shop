// 登录态 + 用户资料。token 走 uni storage（请求层直接读），资料走 pinia persist。
import { defineStore } from "pinia";
import { api } from "@/api";
import { STORAGE } from "@shared/utils/constants";
import { getPushDevice } from "@shared/ports/push";
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
      // App 端绑定推送设备。**不 await**：拿 clientId 要等推送服务初始化，
      // 让登录卡在它上面得不偿失 —— 推送是加速通道，站内信才是必达的
      void this.bindPushDevice();
      return resp.user;
    },

    /** 绑定本机推送标识（仅 App 构建有值）。失败静默：推不到不该影响用户用 app。 */
    async bindPushDevice() {
      const device = await getPushDevice();
      if (!device) return;
      try {
        await api.registerPushToken(device.platform, device.provider, device.clientId);
      } catch {
        // 下次登录会再试一次
      }
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
      /*
       * 解绑设备要在作废会话**之前** —— 之后就没有令牌可用了。
       * 不解绑的后果不是「多推一条」：这台设备换人登录后，
       * 前一个账号的订单会继续推到这里（ADR-018）。
       */
      try {
        const device = await getPushDevice();
        if (device) await api.unregisterPushToken(device.clientId);
      } catch {
        // 解绑失败不拦登出：下一个人登录时的抢占逻辑会兜底（PushTokenService#register）
      }
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
