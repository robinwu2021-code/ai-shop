// 登录态 + 用户资料。token 走 uni storage（请求层直接读），资料走 pinia persist。
import { defineStore } from "pinia";
import { api } from "@/api";
import { silentLoginPayload } from "@shared/ports/auth";
import { STORAGE } from "@shared/utils/constants";
import { getPushDevice } from "@shared/ports/push";
import { useCommunityStore } from "./community";
import type { LoginReq, User } from "@shared/types";

/**
 * 正在进行的静默登录。**模块级而不是 state**：它是一个 promise，
 * 放进 pinia state 会被持久化插件序列化（存进去的是 `{}`），
 * 而这里要的是「能 await 的那个东西」。
 */
let silentInFlight: Promise<boolean> | null = null;

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

    /**
     * 打开小程序即登录，**不需要任何点击**。
     *
     * <p>`wx.login` 换 openid 这一步微信侧不要用户确认，所以「静默」名副其实。
     * 命中已有 openid 就是老用户，没命中就建号（登录即注册）——
     * 两种情况用户都不知道发生过什么，这正是想要的。
     *
     * <p><b>三条边界</b>：
     * <ul>
     *   <li>已有 token 就不做 —— 会话 30 天，每次打开都换一次 token 是白费</li>
     *   <li>失败静默 —— 这只是「顺手认出他」，失败了他照样能逛</li>
     *   <li>只在小程序生效 —— 其它端 `silentLoginPayload()` 返回 null</li>
     * </ul>
     *
     * @return 登录成功返回 true（含「本来就登录着」）
     */
    /**
     * @param force 忽略手里那个 token，强制换一个新的。
     *   401 处理器要用它 —— 那时候「有 token」恰恰不代表「登录着」。
     */
    async silentLogin(force = false) {
      if (this.token && !force) return true;
      /*
       * **把飞行中的那次登录暴露出去**（`silentInFlight`）。
       *
       * 启动时的请求（购物车、资料）会与它赛跑：谁先谁后不确定，
       * 而先跑的那个会拿到 401 —— 然后 401 处理器把人 reLaunch 到登录页，
       * 尽管一秒之后静默登录就成功了。真机实测就是这个样子：
       * **token 已经拿到、账号也建了，用户看到的却是登录页。**
       *
       * 有了这个 promise，401 处理器就能先等一等再决定要不要跳。
       */
      if (silentInFlight) return silentInFlight;
      silentInFlight = (async () => {
        const payload = await silentLoginPayload();
        if (!payload) return false;
        try {
          await this.login(payload as LoginReq);
          return true;
        } catch {
          return false;
        } finally {
          silentInFlight = null;
        }
      })();
      return silentInFlight;
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
