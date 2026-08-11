// 商家登录态 + 资料。对应 C 端的 stores/user —— 但两端是不同的账号体系：
// 同一个人可以既是消费者（C 端 cUserNo）又是商家（B 端 merchantNo），互不影响。
import { defineStore } from "pinia";
import { api } from "@/api";
import { STORAGE } from "@shared/utils/constants";
import type { LoginReq, MerchantProfile, Store } from "@shared/types";

export const useMerchantStore = defineStore("merchant", {
  state: () => ({
    token: "" as string,
    profile: null as MerchantProfile | null,
    /**
     * 当前门店与我有权限的门店。
     *
     * **它是会话上下文，不是某个页面的筛选条件** —— 切一次要在整个 App 里生效，
     * 所以落本地存储、由 http 层统一带 `X-Store-No`，而不是每个页面各传一次参数。
     */
    storeNo: "" as string,
    stores: [] as Store[],
    /**
     * 我在**当前门店**的权限码（后端算好的并集）。老板是 `["*"]`。
     *
     * **切门店后必须重拉** —— 角色跟着门店走，同一个人可能在 A 店是店长、
     * B 店是店员。不重拉的表现是「切到分店后还能看见只有店长才有的入口，
     * 点了报 70006」。
     */
    perms: [] as string[],
    /** 我在当前门店持有的角色，只用于展示（「你是这家店的店员」）。判权一律看 perms */
    staffRoles: [] as string[],
    /** 进行中的 scope 请求，供 ensureScope 去重。不持久化 */
    scopeLoading: null as Promise<unknown> | null,
  }),

  getters: {
    isLogin: (s) => !!s.token,
    /** 只有一家店时不显示切换器 —— 给单店商家一个永远只有一个选项的下拉是纯噪音 */
    multiStore: (s) => s.stores.length > 1,
    currentStore: (s) => s.stores.find((x) => x.storeNo === s.storeNo) ?? null,
    /** 已入驻且正常经营 —— 未通过审核不能上架、不能收款 */
    isActive: (s) => s.profile?.status === "ACTIVE",
    /** 是否承接自提点 → 决定工作台是否出现「履约台」入口（ADR-005） */
    isPickupPoint: (s) => !!s.profile?.isPickupPoint,
    /**
     * 我能不能做这件事。**页面一律用它裁剪入口**，不要自己按角色推 ——
     * 两处各推一次迟早分岔，而分岔的表现是「看得见但点了报错」。
     *
     * 权限还没拉到时返回 false（fail-closed）：宁可少显示一个入口，
     * 也不要先亮出来再消失 —— 后者看着像界面在抽风。
     */
    can: (s) => (code: string) => s.perms.includes("*") || s.perms.includes(code),
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

    /**
     * 员工登录。与 {@link login} 的差别只在打哪个端点 ——
     * 拿到的令牌解析出的是**门店角色**而不是主体属主。
     */
    async staffLogin(phone: string, code: string) {
      const resp = await api.mStaffLogin({ phone, code });
      this.token = resp.token;
      this.profile = resp.merchant;
      uni.setStorageSync(STORAGE.token, resp.token);
      return resp.merchant;
    },

    /**
     * 载入我有权限的门店，并确定当前门店。
     *
     * 本地存的门店号**要校验还在不在**：店被停用、授权被收回之后，
     * 本地那个号会让所有页面查出空数据，而人只会觉得「今天没单」。
     */
    async loadStores() {
      this.stores = await api.mStoreList().catch(() => []);
      const usable = this.stores.filter((s) => s.status === "ACTIVE");
      const saved = (uni.getStorageSync(STORAGE.storeNo) as string) || "";
      const keep = usable.some((s) => s.storeNo === saved) ? saved : "";
      this.switchStore(keep || usable.find((s) => s.isDefault)?.storeNo || usable[0]?.storeNo || "");
      return this.stores;
    },

    switchStore(storeNo: string) {
      this.storeNo = storeNo;
      if (storeNo) uni.setStorageSync(STORAGE.storeNo, storeNo);
      else uni.removeStorageSync(STORAGE.storeNo);
      // 角色跟着门店走 —— 换了店就要重新问「我在这家店能做什么」
      void this.loadScope();
    },

    /**
     * 保证权限已经拉过一次。**幂等，且并发安全**。
     *
     * 为什么需要它：`loadScope` 原先只挂在 `switchStore` 上，而 `switchStore`
     * 只有首页的 `loadStores` 会走。于是**只要不是从首页点进来的**（刷新、
     * tabBar 直接切、深链），`perms` 恒为空 —— 而空 perms 下 `can()` 全是 false，
     * 页面上所有按权限渲染的东西一起消失。
     *
     * 实测形态：老板刷新商品页，新建、编辑、上下架、改库存四个按钮全没了。
     * **判权的默认值是「拒绝」，所以判权状态没加载 = 整个界面被自己锁死。**
     * 这就是为什么它必须挂在所有页面共同的外壳上，而不是靠每个页面自己记得调。
     */
    async ensureScope() {
      if (this.perms.length) return;
      // 多个页面同时挂载时复用同一次请求，不打 N 遍
      this.scopeLoading ??= this.loadScope().finally(() => {
        this.scopeLoading = null;
      });
      await this.scopeLoading;
    },

    /**
     * 拉当前门店的作用域与权限。
     *
     * 失败时**清空权限**而不是保留上一次的：保留会让一个已被收回授权的人
     * 继续看到入口（虽然点了会被后端拒），而清空至少与后端一致。
     */
    async loadScope() {
      if (!this.token) {
        this.perms = [];
        this.staffRoles = [];
        return null;
      }
      try {
        const scope = await api.mBizScope();
        this.perms = scope.perms ?? [];
        this.staffRoles = scope.staffRoles ?? [];
        return scope;
      } catch {
        this.perms = [];
        this.staffRoles = [];
        return null;
      }
    },

    async loadProfile() {
      if (!this.token) return null;
      this.profile = await api.mProfile();
      return this.profile;
    },

    logout() {
      this.token = "";
      this.profile = null;
      // 门店也要清：换个人登录还留着上一位的门店号，是最难查的一类"数据不对"
      this.stores = [];
      this.perms = [];
      this.staffRoles = [];
      this.switchStore("");
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
