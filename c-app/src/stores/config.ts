// 平台下发的运行时配置（`GET /mp/config/bootstrap`）。
//
// **此前端上一次都没调过这条端点** —— 后端在发、运营端能改，而 C 端拿到的
// 只有编译期常量（`FEATURES`）。于是「运营后台改一下开关」对买家侧的任何行为都不成立，
// 要改只能重新发版；小程序还要重新提审。
//
// 现在有一条开关真的需要它：`merchant.apply.mp-visible`（小程序显不显示入驻入口）——
// 那是一条会影响审核的入口，被驳回时要能**立刻关掉止血**，而不是等一个新版本。
import { defineStore } from "pinia";
import { api } from "@/api";

export const useConfigStore = defineStore("config", {
  state: () => ({
    /** 平台开关。yml 与运营端那一屏在后端已经合流，端上只认这一份 */
    features: {} as Record<string, boolean>,
    /**
     * 商家版 App 的下载地址，按平台各一条（后端下发）。
     * **空的那一档端上不显示** —— iOS 还在苹果审核队列里，现在就是空的。
     */
    merchantApp: { android: "", ios: "" },
    loaded: false,
  }),

  getters: {
    /**
     * 开关取值。**拿不到配置时按 `def` 走** ——
     * 一次网络抖动不该把功能悄悄关掉，也不该把该藏的东西放出来：
     * 由调用方按这一条开关的性质决定失败方向。
     */
    flag: (s) => (key: string, def = false) => (key in s.features ? s.features[key]! : def),
  },

  actions: {
    /**
     * 冷启动拉一次。**失败不抛** —— 配置是锦上添花，不能让它把启动挡住。
     *
     * <p>只拉一次：这份配置在一次会话里不会变，而 App 的进程比一次页面加载活得久
     * （`ensure*` 那类「拉过没有」的判断在 App 上等于整段会话都用同一份）。
     * 运营改了开关要下一次冷启动才生效 —— 这是可接受的：止血的量级是分钟，不是秒。
     */
    async load() {
      if (this.loaded) return;
      try {
        const c = await api.bootstrapConfig();
        this.features = c?.features ?? {};
        this.merchantApp = { android: c?.merchantApp?.android ?? "", ios: c?.merchantApp?.ios ?? "" };
        this.loaded = true;
      } catch {
        // 拿不到就保持空表，调用方拿到的是各自的默认值
      }
    },
  },
});
