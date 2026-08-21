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

/**
 * 被拒了要做什么 —— **多半是权限变了，而这一页的入口还是旧的**。
 *
 * 判权在后端是现算的（改完下一个请求就生效），而端上的 `perms` 是
 * 启动那一刻拉的。中间这个窗口里，界面上会留着一个后端已经不允许的按钮。
 * 这是设计上接受的代价 —— 但既然接受，被拒的那一下就必须做两件事：
 * 告诉他发生了什么，以及**把入口收掉**，别让他对着一个点不动的按钮反复点。
 *
 * <p>注意判的是**业务码不是 HTTP 状态**：这套后端除 401 外一律 200 + 包体码
 * （见 GlobalExceptionHandler 的类注释）。
 *
 * <p>C 端不注册它：那边没有 RBAC，只有属主鉴权 ——
 * 「这单不是你的」不会因为刷新一下就变成你的。
 */
let onForbidden: (() => void) | null = null;
let handlingForbidden = false;

/** 10403 通用无权限 · 70006 B 端角色不够（「去找店主」那条） */
const FORBIDDEN_CODES = [10403, 70006];

export function setForbiddenHandler(fn: () => void): void {
  onForbidden = fn;
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
          if (FORBIDDEN_CODES.includes(body.code) && onForbidden && !handlingForbidden) {
            handlingForbidden = true;
            onForbidden();
            setTimeout(() => (handlingForbidden = false), 0);
          }
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

/**
 * 文件上传。**不能用 {@link request}** —— 那是 uni.request（JSON body），
 * 后端 `/biz/upload/image` 要的是 multipart 文件流（`@RequestParam("file") MultipartFile`）。
 * 之前 mUploadImage 走 http.post 把**本地临时路径字符串**当 JSON 发过去，
 * 服务端拿不到文件，真机上「上传图片不能用」就是这么来的（mock 下返假 URL 才看不出）。
 *
 * @param filePath uni.chooseImage 给的端上临时路径
 * @param formData 附加表单字段（如 bizType）。**不要手写 Content-Type** ——
 *                 uploadFile 自己按 multipart 组 boundary，手设会破坏它。
 */
export function uploadFile<T>(
  path: string,
  filePath: string,
  formData?: Record<string, string>,
): Promise<T> {
  const token = uni.getStorageSync(STORAGE.token) as string;
  const storeNo = uni.getStorageSync(STORAGE.storeNo) as string;
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: `${BASE}${path}`,
      filePath,
      name: "file",
      formData,
      header: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(storeNo ? { "X-Store-No": storeNo } : {}),
      },
      success(res) {
        /*
         * **先看状态码再解析包体** —— 顺序反了就把所有「没有包体的失败」
         * 说成了「响应格式不符合契约」。最常撞上的是 413：容器在进 Controller
         * 之前就拒了超大文件，响应体是空的，于是商家传一张 2MB 的商品照片，
         * 得到的提示是「响应格式不符合契约」，一个字都没说到大小上。
         */
        if (res.statusCode === 413) {
          reject(new ApiError(413, "图片太大，请换一张小一点的（最大 5MB）"));
          return;
        }
        // uploadFile 的响应体是**字符串**，要自己解析
        let body: Result<T>;
        try {
          body = JSON.parse(res.data as string) as Result<T>;
        } catch {
          reject(new ApiError(-1, "响应格式不符合契约"));
          return;
        }
        if (res.statusCode === 401) {
          uni.removeStorageSync(STORAGE.token);
          if (onUnauthorized && !handling) {
            handling = true;
            onUnauthorized();
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
          reject(new ApiError(body.code, body.msg || "上传失败"));
          return;
        }
        resolve(body.data);
      },
      fail(err) {
        reject(new ApiError(-1, err.errMsg || "上传失败"));
      },
    });
  });
}

export const http = {
  // 入参用 object 而非 Record<string, unknown>：契约里的 payload 是具名接口
  // （LoginReq / GoodsDraft…），具名接口没有索引签名，用 Record 会在每个调用点报错。
  get: <T>(path: string, params?: object) => request<T>("GET", path, params),
  post: <T>(path: string, data?: object) => request<T>("POST", path, data),
  uploadFile,
};

/** 幂等 key：下单等写操作必带，防重复提交 */
export function idempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}
