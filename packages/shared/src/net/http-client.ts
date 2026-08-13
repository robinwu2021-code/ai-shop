// HTTP 传输层：uni.request 的最小封装（超时、鉴权头、错误归一）。
//
// 两端原来各有一份，逐字节相同，只差一个入参类型 —— 这里取更宽的那个（见 get/post 上的注释）。
// 它不属于 UI，所以放在 shared 而不是 ui：ops-web 之外的任何端都可能用到。
import { STORAGE } from "@shared/utils/constants";
import type { Result } from "@shared/types";

const BASE = import.meta.env.VITE_API_BASE || "";

export class ApiError extends Error {
  constructor(
    public code: number,
    msg: string,
  ) {
    super(msg);
  }
}

type Method = "GET" | "POST" | "PUT";

/**
 * 登录失效时做什么 —— **由各端在 App 壳上注册一次**。
 *
 * 为什么放在传输层而不是各页各自 catch：401 可能从任何一个请求上回来，
 * 而「哪一页发的请求」与「该去哪」无关。C 端此前一处也没接，
 * 令牌一过期（重启后端、放一夜）整页就是空白 + 一个未捕获错误 ——
 * 没有提示、没有跳转，刷新也一样，因为 token 还在存储里躺着。
 * B 端接过，但只接在 `ensureScope` 一处，别的请求上的 401 同样什么也不发生。
 */
let onUnauthorized: (() => void) | null = null;
/** 同一轮里只触发一次：一屏并发三个请求就跳三次，会把提示刷掉、把路由搅乱 */
let handling = false;

export function setUnauthorizedHandler(fn: () => void): void {
  onUnauthorized = fn;
}

export function request<T>(
  method: Method,
  path: string,
  data?: object,
): Promise<T> {
  const token = uni.getStorageSync(STORAGE.token) as string;
  /*
   * B 端当前门店。**放请求头而不是每个接口加参数** —— 它是整个会话的上下文，
   * 不是某个查询的条件；加成参数的话每加一个接口都要记得带，漏一个就静默看错门店。
   * C 端没有这个值，读出来是空字符串，不会带上。
   */
  const storeNo = uni.getStorageSync(STORAGE.storeNo) as string;
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${BASE}${path}`,
      method,
      data: data as Record<string, unknown> | undefined,
      header: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(storeNo ? { "X-Store-No": storeNo } : {}),
      },
      success(res) {
        const body = res.data as Result<T>;
        if (res.statusCode === 401) {
          uni.removeStorageSync(STORAGE.token);
          if (onUnauthorized && !handling) {
            handling = true;
            onUnauthorized();
            // 下一轮宏任务放开：这一屏的并发请求算同一轮，用户下次点才是新一轮
            setTimeout(() => (handling = false), 0);
          }
          reject(new ApiError(401, "登录已失效，请重新登录"));
          return;
        }
        if (!body || typeof body.code !== "number") {
          reject(new ApiError(-1, "响应格式不符合契约"));
          return;
        }
        if (body.code !== 0) {
          reject(new ApiError(body.code, body.msg || "请求失败"));
          return;
        }
        resolve(body.data);
      },
      fail(err) {
        reject(new ApiError(-1, err.errMsg || "网络异常"));
      },
    });
  });
}

export const http = {
  // 入参用 object 而非 Record<string, unknown>：契约里的 payload 是具名接口
  // （LoginReq / GoodsDraft…），具名接口没有索引签名，用 Record 会在每个调用点报错。
  get: <T>(path: string, params?: object) => request<T>("GET", path, params),
  post: <T>(path: string, data?: object) => request<T>("POST", path, data),
};

/** 幂等 key：下单等写操作必带，防重复提交 */
export function idempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}
