// mock ↔ 真实后端一键切换（唯一开关）。页面统一 `import { api } from "@/api"`。
import type { MerchantApi } from "./contract";
import { mockApi } from "./mock";
import { httpApi } from "./http";

export const USE_MOCK = import.meta.env.VITE_USE_MOCK !== "0";

export const api: MerchantApi = USE_MOCK ? mockApi : httpApi;

export type { MerchantApi, GoodsDraft } from "./contract";
export { ApiError } from "@shared/net/http-client";
