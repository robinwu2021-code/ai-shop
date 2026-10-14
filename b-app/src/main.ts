import { createSSRApp } from "vue";
import { createPinia } from "pinia";
import { createPersistedState } from "pinia-plugin-persistedstate";
import { uniPersistStorage } from "@shared/ports/persist";
import App from "./App.vue";
import { i18n } from "./i18n";
import { installErrorReporting } from "./shared/report-error";
import "virtual:uno.css";

export function createApp() {
  const app = createSSRApp(App);
  const pinia = createPinia();
  /*
   * 持久化走 **uni 存储**（见 ports/persist.ts）：插件默认的 `localStorage`
   * 在 App 运行时不存在，用它则冷启后 `merchant.profile` 恢复不了、掉回「未登录」
   * （H5 正常，只在 App 裂）。注：主题/语言不走本插件，各自 `uni.setStorageSync` 直存。
   */
  pinia.use(createPersistedState({ storage: uniPersistStorage }));
  app.use(pinia);
  app.use(i18n);
  /*
   * **错误上报要在最前面接**，别等某个页面自己 try/catch。
   * 这个 App 此前一个处理器都没有：真机上整页空白而 logcat 零信号，
   * 查不出根因是因为它拒绝报告（见 shared/report-error.ts）。
   */
  installErrorReporting(app);
  return { app, pinia };
}
