/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_USE_MOCK: string;
  readonly VITE_API_BASE: string;
  /** 本地存储命名空间（she）—— 与另外两端同域时也不互串 */
  readonly VITE_APP_NS: string;
  /** 订阅消息模板号（询价结果 / 有新求购 / 被选中共用一个）；空 = 不弹授权 */
  readonly VITE_WX_TPL_ELEC_QUOTED: string;
  /** 路由前缀。独立发布为空；并进 c-app 测试时是 /pkg-elec（构建脚本注入） */
  readonly VITE_ELEC_ROUTE_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent<{}, {}, any>;
  export default component;
}

declare const __BUILD_VERSION__: string;
