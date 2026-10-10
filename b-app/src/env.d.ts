/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_USE_MOCK: string;
  readonly VITE_API_BASE: string;
  /** 本地存储命名空间（shc / shb）—— 两端同域时也不互串 */
  readonly VITE_APP_NS: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent<{}, {}, any>;
  export default component;
}

/** vite define 注入的构建版本号（versionName · 构建时刻），见 vite.config.mts */
declare const __BUILD_VERSION__: string;
