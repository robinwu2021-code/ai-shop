// 真实后端 fetch 封装：拼身份头 + Accept-Language + 统一 Result<T> 拆包 + 抛 ApiError。
// 契约口径见 lib/types/common.ts（{code,msg,data}），与 C 端一致。
import { currentAuth, refreshPerms, scopeOf, useAuth } from "../auth";
import { notify } from "../notify";
import type { Result } from "../types";
import { ApiError } from "./error";
import { useLocaleStore } from "../stores/locale";
import { translate, LOCALE_TAG } from "../i18n";

function curLocale() {
  return useLocaleStore.getState().locale;
}
// HTTP 状态 → i18n 错误 key（后端有本地化 message 时优先用后端的）。
function statusKey(status: number): string {
  return status === 401 ? "error.unauthorized"
    : status === 403 ? "error.forbidden"
    : status === 404 ? "error.notFound"
    : status === 400 ? "error.badRequest"
    : status >= 500 ? "error.serverError"
    : "error.unknown";
}

// 端点路径已自带 /ops 前缀（见 https/*.ts），故 BASE 是后端「源」而非 /ops：
// - 同源 nginx 反代（生产）：留空 → 走同源 /ops/**；
// - 跨源本地开发：置后端源 http://localhost:8080。
const BASE = process.env.NEXT_PUBLIC_API_BASE || "";

function headers(): Record<string, string> {
  const a = currentAuth();
  const scope = scopeOf(a);
  // ⚠️ 这些头是**给后端做日志与灰度的线索**，不是鉴权依据。
  // 角色与数据域以 Bearer 里的声明为准（矩阵 §2.3：不靠前端隐藏，更不靠前端声明）。
  return {
    "Content-Type": "application/json",
    "Accept-Language": LOCALE_TAG[curLocale()], // 让后端按语言返回本地化 msg
    "X-Tenant-No": scope.tenantNo,
    "X-User-Id": a?.username ?? "",
    ...(a?.token ? { Authorization: `Bearer ${a.token}` } : {}),
  };
}

/**
 * 登录失效：**清登录态 + 回登录页**。
 *
 * 此前这里只是把 401 包成 ApiError 抛出去，而各页把它当成一次普通的取数失败 ——
 * 实测形态：运营打开评价审核，页面写着
 * <b>「待审队列已清空。用户发表新评价后会重新出现在这里。」</b>
 * 而队列里躺着 4 条评价，他只是登录过期了。**这句话不是「没数据」，是一句假话** ——
 * 他会据此以为平台没人在评价，而不是去重新登录。
 *
 * （`useRefreshPerms` 的注释里写着「真的失效了（401）由 http 层统一处理」——
 * 那时候 http 层并没有处理。注释描述了一个不存在的机制，比没有注释更误导。）
 *
 * 同一轮只跑一次：一屏并发几个请求会连着跳几次，把提示刷掉、把路由搅乱。
 */
let handlingUnauthorized = false;

function onUnauthorized(): void {
  if (typeof window === "undefined" || handlingUnauthorized) return;
  handlingUnauthorized = true;
  useAuth.getState().logout();
  notify.error(translate(curLocale(), "error.unauthorized"));
  // 下一个宏任务放开：这一屏的并发请求算同一轮
  setTimeout(() => (handlingUnauthorized = false), 0);
  /*
   * **要带 basePath。** 这是整页跳转，不走 Next 路由，所以 next.config 里的
   * basePath 不会替我们加上去 —— 子路径部署（/ops-web/）下写死 "/login"
   * 会跳到站点根目录，那是官网，用户看到的是一个毫不相干的页面而不是登录框。
   * 同源部署时 BASE 为空串，行为与从前一致。
   */
  const BASE = process.env.NEXT_PUBLIC_BASE_PATH || "";
  const login = `${BASE}/login`;
  if (!window.location.pathname.startsWith(login)) {
    window.location.href = login;
  }
}

/**
 * 被拒了：**多半是权限变了，而这一页的入口还是旧的**。
 *
 * 判权在后端是现算的，端上的 `perms` 却是登录那一刻的快照 ——
 * 中间隔着一个最长 60 秒的窗口（服务端快照 TTL），跨实例还要再加一个。
 * 所以「点了一个界面上还在、后端已经不允许的按钮」是这套设计**接受的**代价。
 *
 * 既然接受，就必须在被拒的那一下说清楚。原先只弹一句后端的「没有操作权限」——
 * 它读起来像「你本来就不能做这件事」，而真相往往是「你刚刚还能，现在不能了」。
 * 前者让人去找管理员要权限，后者只需要刷新一下页面。
 *
 * <p>顺手把 perms 拉一遍：拉到之后那个按钮会自己消失，
 * 他不会对着一个点不动的按钮反复点。
 */
function onForbidden(): void {
  if (typeof window === "undefined") return;
  notify.error(translate(curLocale(), "error.permChanged"));
  void refreshPerms();
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  let r: Response;
  try {
    r = await fetch(`${BASE}${path}`, { ...init, headers: { ...headers(), ...init?.headers } });
  } catch {
    throw new ApiError(-1, translate(curLocale(), "error.network")); // 网络层失败
  }
  const body = (await r.json().catch(() => ({}))) as Partial<Result<T>>;
  if (r.status === 401) {
    onUnauthorized();
  }
  if (r.status === 403) {
    onForbidden();
  }
  if (!r.ok || (body.code !== undefined && body.code !== 0)) {
    // 后端已本地化 msg 优先；否则用状态码映射的 i18n 文案
    const msg = body.msg || translate(curLocale(), statusKey(r.status));
    throw new ApiError(body.code ?? r.status, msg);
  }
  return body.data as T;
}

export const client = {
  get: <T>(path: string, q?: object) => req<T>(`${path}${qs(q)}`),
  post: <T>(path: string, data?: unknown) => req<T>(path, { method: "POST", body: JSON.stringify(data ?? {}) }),
  put: <T>(path: string, data?: unknown) => req<T>(path, { method: "PUT", body: JSON.stringify(data ?? {}) }),
};

function qs(q?: object): string {
  if (!q) return "";
  const p = new URLSearchParams(
    Object.entries(q as Record<string, unknown>).filter(([, v]) => v != null && v !== "").map(([k, v]) => [k, String(v)]),
  );
  const s = p.toString();
  return s ? `?${s}` : "";
}
