// 唯一切换点：NEXT_PUBLIC_USE_MOCK=0 走真实后端，否则 mock。
// 页面统一 `import { api } from "@/lib/api"`，调用 api.xxx()，**不感知 mock/真实**。
import type { Api } from "./contract";
import { mockApi } from "./mock";
import { httpApi } from "./http";
import { IS_MOCK } from "../api-mode";

export const api: Api = IS_MOCK ? mockApi : httpApi;
// 判据只有一份，在 lib/api-mode.ts —— 根 layout 也要读它（见那里的注释）。
// 这里转出去，是为了既有的 `import { IS_MOCK } from "@/lib/api"` 不用改。
export { IS_MOCK } from "../api-mode";
export type { Api } from "./contract";
export * from "./contract";
