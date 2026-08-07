// mock ↔ 真实后端一键切换（唯一开关）。
// 页面统一 `import { api } from "@/api"`，**零 if (USE_MOCK)**。
// 切换只改 .env 的 VITE_USE_MOCK，业务代码零改动。
import type { ShopApi } from "./contract";
import { mockApi } from "./mock";
import { httpApi } from "./http";

export const USE_MOCK = import.meta.env.VITE_USE_MOCK !== "0";

export const api: ShopApi = USE_MOCK ? mockApi : httpApi;

export type { ShopApi, GoodsQuery, CreateOrderReq } from "./contract";
export { ApiError, idempotencyKey } from "@shared/net/http-client";
