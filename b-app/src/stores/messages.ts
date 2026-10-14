// 未读消息角标（TDD-通知与消息推送 §二期）。
// 30s 轮询 unread-count —— 一期规模（单城市几百商家）下轮询完全够，
// 「秒达」由三期的 App 推送弥补；WebSocket 是四期观察后再议的事。
import { ref } from "vue";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";

const POLL_MS = 30_000;

export const unreadCount = ref(0);

let timer: ReturnType<typeof setInterval> | undefined;

export async function refreshUnread(): Promise<void> {
  // 没登录不打（会白挨一个 401，还触发全局登出跳转）
  if (!useMerchantStore().isLogin) {
    unreadCount.value = 0;
    return;
  }
  try {
    unreadCount.value = await api.mMessageUnread();
  } catch {
    // 角标查询失败保持旧值：轮询下一轮自然重试，弹错误 toast 只会骚扰人
  }
}

/** App onShow 时开始（先立即刷一次），onHide 停 —— 后台的 app 不该空耗电量与请求。 */
export function startUnreadPolling(): void {
  stopUnreadPolling();
  void refreshUnread();
  timer = setInterval(() => void refreshUnread(), POLL_MS);
}

export function stopUnreadPolling(): void {
  if (timer) {
    clearInterval(timer);
    timer = undefined;
  }
}
