/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_USE_MOCK: string;
  readonly VITE_API_BASE: string;
  readonly VITE_MAP_KEY: string;
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

/**
 * 构建版本号（vite define 注入，见 vite.config.mts）。
 * 形如 `0.1.1 · 0904-1955` —— 后半段是构建时刻，
 * 它保证这个数**每次构建都不同**，因而能回答「我手上这份是不是刚传的那一版」。
 */
declare const __BUILD_VERSION__: string;
