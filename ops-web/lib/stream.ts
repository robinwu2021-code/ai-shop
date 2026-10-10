// 运营端的服务端推送客户端。
//
// **为什么不用浏览器原生的 EventSource**：它不支持自定义请求头，而运营端的会话
// 在 `Authorization: Bearer` 里。把令牌塞进 query 不可接受 —— 它会进 nginx 访问
// 日志、进浏览器历史、进 Referer。所以这里用 fetch + ReadableStream 自己读 SSE 帧。
//
// 帧格式只用到两行：`event: <名字>` 与 `data: <一行 JSON>`，以空行分隔。
// 后端不发多行 data，所以解析器刻意不支持多行 —— 支持了就要处理拼接，
// 而没有任何一处会用到，那段代码将来只会被读到、不会被执行。
import { currentAuth } from "./auth";

const BASE = process.env.NEXT_PUBLIC_API_BASE || "";

type Handler = (event: string, data: string) => void;

/** 断线重连的退避。**上限不能太大** —— 后端重启一次，页面不该沉默五分钟。 */
const BACKOFF_MS = [1000, 2000, 5000, 10_000, 15_000];

export type StreamHandle = { close: () => void };

/**
 * 连上 `/ops/stream`，把每个事件交给 `onEvent`。
 *
 * 返回的 `close()` 必须在组件卸载时调用 —— 不调的话每次路由切换都会多留一条
 * 连接，而后端那侧看到的是「在线人数只增不减」。
 */
export function openOpsStream(onEvent: Handler): StreamHandle {
  let closed = false;
  let attempt = 0;
  let controller: AbortController | null = null;

  async function connect(): Promise<void> {
    if (closed) return;
    const token = currentAuth()?.token;
    if (!token) {
      // 还没登录：不重试，等页面自己在登录后重新挂载
      return;
    }
    controller = new AbortController();
    try {
      const res = await fetch(`${BASE}/ops/stream`, {
        headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
        signal: controller.signal,
      });
      if (!res.ok || !res.body) {
        throw new Error(`stream ${res.status}`);
      }
      attempt = 0;   // 连上了才清退避，否则一次成功握手会掩盖持续的失败
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done || closed) break;
        buf += decoder.decode(value, { stream: true });
        // 按空行切帧。**必须用剩余量续着解**，一帧可能跨两个 chunk
        let sep: number;
        while ((sep = buf.indexOf("\n\n")) >= 0) {
          const frame = buf.slice(0, sep);
          buf = buf.slice(sep + 2);
          let name = "message";
          let data = "";
          for (const line of frame.split("\n")) {
            if (line.startsWith("event:")) name = line.slice(6).trim();
            else if (line.startsWith("data:")) data = line.slice(5).trim();
          }
          if (data) onEvent(name, data);
        }
      }
    } catch {
      // 网络断了、后端重启了、连接被代理掐了 —— 都是同一件事：重连。
      // **不弹提示**：运营端是常开页面，一次后端发布就弹一次错误只会让人忽略提示
    }
    if (closed) return;
    const wait = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
    attempt += 1;
    setTimeout(() => void connect(), wait);
  }

  void connect();
  return {
    close() {
      closed = true;
      controller?.abort();
    },
  };
}
