// E2E-2 的 HTTP 客户端：**按端点表发请求**，不硬编码路径。
//
// 为什么不直接用 b-app 的 `http.ts`：它绑死 `uni.request`，Node 里没有。
// 但路径与形状我们仍然取自端上那两份声明 —— 于是端改了路径，这里立刻按新路径打，
// 后端没跟上就红。这正是「静态比对」升级成「真的打一次」的地方。
import { loadEndpoints, loadOpenapi, checkShape } from "./contract.mjs";

const BASE = process.env.E2E_BASE ?? "http://localhost:8080";

const TABLES = {
  "c-app": loadEndpoints("c-app"),
  "b-app": loadEndpoints("b-app"),
};
const SPECS = {
  "c-app": loadOpenapi("openapi.yaml"),
  "b-app": loadOpenapi("openapi-b.yaml"),
};

/** 累计的形状问题。跑完一次性报 —— 逐条抛的话第一个就中断，看不到全貌 */
export const shapeIssues = [];

export class E2eError extends Error {
  constructor(message, context) {
    super(message);
    this.context = context;
  }
}

/**
 * 会话：一个人的令牌 + 他当前所在的门店。
 *
 * 做成对象而不是全局变量：一条旅程里同时有买家、商家、运营三个人在动，
 * 全局令牌会让「谁在调这个接口」变成一件要靠上下文猜的事。
 */
export function session(name) {
  return { name, token: null, storeNo: null };
}

/** 填路径参数：端上写的是 `:orderNo`，这里按名字替换 */
function fillPath(template, params) {
  let p = template;
  for (const [k, v] of Object.entries(params ?? {})) {
    p = p.replace(`:${k}`, encodeURIComponent(v));
  }
  const left = p.match(/:(\w+)/);
  if (left) throw new E2eError(`路径参数 ${left[1]} 没给值`, { template, params });
  return p;
}

/**
 * 按端点键调用。
 *
 * @param app     "c-app" | "b-app"
 * @param key     endpoints.ts 里的键，如 `mOrderDetail`
 * @param opts    { sess, params, query, body, expectFail }
 */
export async function call(app, key, opts = {}) {
  const ep = TABLES[app]?.[key];
  if (!ep) {
    throw new E2eError(`${app} 的端点表里没有 ${key} —— 端上是不是改名了？`, { app, key });
  }
  const { sess, params, query, body, expectFail = false } = opts;

  let url = BASE + fillPath(ep.path, params);
  if (query && Object.keys(query).length) {
    url += "?" + new URLSearchParams(query).toString();
  }

  const headers = { "Content-Type": "application/json" };
  if (sess?.token) headers.Authorization = `Bearer ${sess.token}`;
  // 与端上同一个头：当前门店是会话上下文，不是查询条件
  if (sess?.storeNo) headers["X-Store-No"] = sess.storeNo;

  const res = await fetch(url, {
    method: ep.method,
    headers,
    body: ep.method === "POST" ? JSON.stringify(body ?? {}) : undefined,
  });
  const text = await res.text();

  let envelope;
  try {
    envelope = JSON.parse(text);
  } catch {
    throw new E2eError(`${ep.method} ${ep.path} 返回的不是 JSON（HTTP ${res.status}）`, { text });
  }
  if (typeof envelope.code !== "number") {
    throw new E2eError(`${ep.method} ${ep.path} 不符合统一信封`, { text });
  }

  if (expectFail) {
    if (envelope.code === 0) {
      throw new E2eError(`${key} 本该被拒，却成功了`, { text });
    }
    return { code: envelope.code, msg: envelope.msg };
  }
  if (envelope.code !== 0) {
    throw new E2eError(`${key}（${ep.method} ${ep.path}）失败：${envelope.msg}`, { text });
  }

  // ★ 形状校验：端声明它要哪些字段，后端真的给了吗
  const ref = SPECS[app].dataRefOf[key];
  if (ref) {
    const missing = checkShape(SPECS[app], ref, envelope.data);
    for (const m of missing) {
      shapeIssues.push(`${app}.${key} → 缺字段 ${m}`);
    }
  }
  return envelope.data;
}

/**
 * 运营端调用。
 *
 * <p>为什么不像 C/B 端那样走端点表：ops-web 的端点声明在
 * `ops-web/lib/api/https/*.ts` 里，是 `client.get("/ops/…")` 这种调用式写法，
 * 不是一张可以直接读的表。**所以这里是全篇唯一硬编码路径的地方** ——
 * 它是已知的短板，不是随手写的：ops-web 改了路径，这里不会自动跟着红。
 *
 * @param opts { token, body, query, expectFail }
 */
export async function opsCall(method, path, opts = {}) {
  const { token, body, query, expectFail = false } = opts;
  let url = BASE + path;
  if (query && Object.keys(query).length) {
    url += "?" + new URLSearchParams(query).toString();
  }
  const res = await fetch(url, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: method === "GET" ? undefined : JSON.stringify(body ?? {}),
  });
  const text = await res.text();
  let envelope;
  try {
    envelope = JSON.parse(text);
  } catch {
    throw new E2eError(`${method} ${path} 返回的不是 JSON（HTTP ${res.status}）`, { text });
  }
  if (expectFail) {
    if (envelope.code === 0) {
      throw new E2eError(`${method} ${path} 本该被拒，却成功了`, { text });
    }
    return { code: envelope.code, msg: envelope.msg };
  }
  if (envelope.code !== 0) {
    throw new E2eError(`${method} ${path} 失败：${envelope.msg}`, { text });
  }
  return envelope.data;
}

/** 运营登录，返回令牌 */
export async function opsLogin(username = "admin", password = "admin123") {
  const data = await opsCall("POST", "/ops/auth/login", { body: { username, password } });
  return data.token;
}

/** 支付回调：面向通道，**不走统一信封**，所以不能用 call() */
export async function payCallback(payOrderNo) {
  const res = await fetch(`${BASE}/callback/pay/stub`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      outTradeNo: payOrderNo,
      transactionId: `TX-${payOrderNo}`,
      sign: "stub-secret",
    }),
  });
  if (!res.ok) throw new E2eError(`支付回调失败 HTTP ${res.status}`, { payOrderNo });
}

/** 服务端在不在。**先探一下再跑** —— 否则第一条旅程会报一个看不懂的连接错误 */
export async function ping() {
  try {
    const res = await fetch(`${BASE}/common/master-data`);
    return res.ok;
  } catch {
    return false;
  }
}
