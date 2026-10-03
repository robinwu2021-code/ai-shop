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
  // 持久化走 uni 存储：插件默认的 localStorage 在 App 运行时不存在，
  // 用默认适配器时购物车 / 登录态 / 社区选择在 App 冷启后全丢（见 ports/persist.ts）
  pinia.use(createPersistedState({ storage: uniPersistStorage }));
  app.use(pinia);
  app.use(i18n);
  return { app, pinia };
}
