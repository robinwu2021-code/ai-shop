// 登录态。账号是主系统的（与「社区好物」同一个账号体系），元器件只借它的令牌。
// 写法照 c-app/src/stores/user.ts，删掉了社区、邀请、推送那些与元器件无关的部分。
import { defineStore } from "pinia";
import { api } from "@/api";
import { silentLoginPayload } from "@shared/ports/auth";
import { STORAGE } from "@shared/utils/constants";
import type { LoginReq, User } from "@shared/types";

/** 飞行中的那次静默登录（模块级：promise 不能进 pinia state，会被持久化成 `{}`） */
let silentInFlight: Promise<boolean> | null = null;

export const useUserStore = defineStore("elecUser", {
  state: () => ({
    token: "" as string,
    user: null as User | null,
  }),

  getters: {
    isLogin: (s) => !!s.token,
    /** 绑过手机号没有。询价与成为供应商都要它 —— 平台要打得通这个电话 */
    hasPhone: (s) => !!s.user?.phone,
  },

  actions: {
    restore() {
      this.token = (uni.getStorageSync(STORAGE.token) as string) || "";
    },

    /**
     * 打开即认人（只在小程序生效）。
     *
     * ⚠️ 独立小程序的 appid 还没接进主系统时，这一步换不出 openid（code 属于另一个 appid），
     * 会静默失败 —— 于是退到手机号登录，功能不受影响，只是少了「无感」。
     */
    async silentLogin(force = false) {
      if (this.token && !force) return true;
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
      return resp.user;
    },

    async loadProfile() {
      if (!this.token) return null;
      this.user = await api.profile();
      return this.user;
    },

    clearSession() {
      this.token = "";
      this.user = null;
      uni.removeStorageSync(STORAGE.token);
    },
  },

  persist: {
    // 不与 c-app 的用户 store 共用 STORAGE.user：测试期并进 c-app 时两个 store 同在一个进程里，
    // 同一个键会互相覆盖（c-app 那份还存着邀请人）。令牌本来就共用 STORAGE.token，那是有意的
    key: `${STORAGE.user}_elec`,
    pick: ["user"],
  },
});
