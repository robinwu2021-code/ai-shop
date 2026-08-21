import { createSSRApp } from "vue";
import { createPinia } from "pinia";
import { createPersistedState } from "pinia-plugin-persistedstate";
import { uniPersistStorage } from "@shared/ports/persist";
import App from "./App.vue";
import { i18n } from "./i18n";
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
  return { app, pinia };
}
