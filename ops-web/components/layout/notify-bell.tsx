"use client";

// 顶栏铃铛（TDD-通知与消息推送 §二期）：运营自己的通知收件箱。
//
// 未读数走**服务端推送**（/ops/stream 的 unread 事件），不再轮询。
// 原先每 15 秒问一次：一个人开着页面一天 2000 多次请求，其中绝大多数拿回
// 一模一样的数字。轮询并没有消失，只是挪到了服务端一个循环里，且只在变了时才推。
// Web Push（浏览器关了也能达）是四期观察后再议的事。
//
// 浏览器桌面横幅：未读数**上涨**时弹一条（新工单/待审核/告警都值得把人从别的
// 标签页拉回来）。权限只在用户第一次点开铃铛时请求 —— 页面一加载就弹权限框，
// 大多数人会条件反射点「拒绝」，之后就再也弹不出来了。
import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";
import { useOpsStream } from "@/lib/use-ops-stream";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import { Bell } from "lucide-react";
import type { InboxMessage } from "@/lib/types";

const SHOWN = 10;

function fmt(at: number): string {
  const d = new Date(at);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function NotifyBell() {
  const loggedIn = useAuth((s) => !!s.token);
  const { t } = useI18n();
  const router = useRouter();
  const [unread, setUnread] = useState(0);
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<InboxMessage[]>([]);
  const prevUnread = useRef(0);

  const applyUnread = useCallback((n: number) => {
    if (!Number.isFinite(n)) return;   // 帧坏了就当没收到，别把角标变成 NaN
    // 数字上涨 = 有新东西。弹桌面横幅把人从别的标签页拉回来；
    // 具体内容不放进横幅 —— 桌面通知可能出现在投屏/共享屏幕上
    if (n > prevUnread.current && typeof Notification !== "undefined"
        && Notification.permission === "granted" && document.visibilityState !== "visible") {
      new Notification(t("bell.desktopTitle"), { body: t("bell.desktopBody", { n }), tag: "ops-inbox" });
    }
    prevUnread.current = n;
    setUnread(n);
  }, [t]);

  // 推送来的就是数字本身，不必再发一次请求去拿
  useOpsStream("unread", (d) => applyUnread(Number(d)), loggedIn);

  /*
   * 标记已读之后主动拉一次。**这不是轮询** —— 它跟着用户的一次点击走。
   * 只等推送的话，角标要过最多 3 秒才掉下去，而人刚点完「全部已读」
   * 就盯着那个红点看，3 秒足够让他以为没生效、再点一次。
   */
  const refreshUnread = useCallback(async () => {
    try {
      applyUnread(await api.inboxUnread());
    } catch { /* 推送下一轮会自然校正 */ }
  }, [applyUnread]);

  async function onOpenChange(next: boolean) {
    setOpen(next);
    if (!next) return;
    // 首次点开时请求桌面通知权限：这是用户主动表达「我关心通知」的时刻
    if (typeof Notification !== "undefined" && Notification.permission === "default") {
      void Notification.requestPermission();
    }
    try {
      setItems((await api.listInbox()).slice(0, SHOWN));
    } catch {
      setItems([]);
    }
  }

  async function onItemClick(m: InboxMessage) {
    setOpen(false);
    if (!m.read) {
      try {
        await api.readInbox(m.messageNo);
        await refreshUnread();
      } catch { /* 已读失败不拦跳转 */ }
    }
    if (m.link) router.push(m.link);
  }

  async function onReadAll() {
    try {
      setItems((await api.readAllInbox()).slice(0, SHOWN));
      await refreshUnread();
    } catch { /* 推送会自然校正 */ }
  }

  if (!loggedIn) return null;

  return (
    <Popover open={open} onOpenChange={(v) => void onOpenChange(v)}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={t("bell.aria")}
          className="relative rounded-field p-1.5 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
        >
          <Bell className="size-4" />
          {unread > 0 && (
            <span className="absolute -top-0.5 -end-0.5 flex h-4 min-w-4 items-center justify-center rounded-chip bg-destructive px-1 text-[10px] font-medium leading-none text-white">
              {unread > 99 ? "99+" : unread}
            </span>
          )}
        </button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-80 p-0">
        <div className="flex items-center justify-between border-b border-border px-3 py-2">
          <span className="txt-body font-medium">{t("bell.title")}</span>
          {unread > 0 && (
            <Button size="sm" variant="ghost" onClick={() => void onReadAll()}>
              {t("bell.readAll")}
            </Button>
          )}
        </div>
        <div className="max-h-96 overflow-y-auto">
          {items.length === 0 && (
            <div className="px-3 py-8 text-center txt-body text-muted-foreground">{t("bell.empty")}</div>
          )}
          {items.map((m) => (
            <button
              key={m.messageNo}
              type="button"
              onClick={() => void onItemClick(m)}
              className="flex w-full items-start gap-2 border-b border-border px-3 py-2.5 text-start transition-colors last:border-b-0 hover:bg-accent"
            >
              <span
                className={`mt-1.5 size-1.5 flex-shrink-0 rounded-chip ${m.read ? "bg-transparent" : "bg-destructive"}`}
              />
              <span className="min-w-0 flex-1">
                <span className="flex items-baseline justify-between gap-2">
                  <span className={`truncate txt-body ${m.read ? "text-muted-foreground" : "font-medium"}`}>
                    {m.title}
                  </span>
                  <span className="flex-shrink-0 txt-caption text-muted-foreground">{fmt(m.at)}</span>
                </span>
                <span className="mt-0.5 block truncate txt-caption text-muted-foreground">{m.body}</span>
              </span>
            </button>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  );
}
