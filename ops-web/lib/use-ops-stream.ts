"use client";

// 订阅服务端推送的某一类事件。整个页面共用一条连接 —— 每个组件各开一条的话，
// 后端看到的在线数是「打开的组件数」而不是「打开的人数」，而那个数会被用来判断负载。
import { useEffect, useRef } from "react";
import { openOpsStream, type StreamHandle } from "./stream";

type Listener = (data: string) => void;

let handle: StreamHandle | null = null;
let refCount = 0;
const listeners = new Map<string, Set<Listener>>();

function dispatch(event: string, data: string) {
  listeners.get(event)?.forEach((fn) => {
    try {
      fn(data);
    } catch {
      // 一个订阅者抛错不能让其余的收不到这一帧
    }
  });
}

function acquire() {
  refCount += 1;
  if (!handle) handle = openOpsStream(dispatch);
}

function release() {
  refCount -= 1;
  if (refCount <= 0) {
    handle?.close();
    handle = null;
    refCount = 0;
  }
}

/**
 * 订阅一类事件。`enabled=false` 时不连 —— 未登录的页面不该建连接。
 *
 * 回调用 ref 存：把它放进 deps 会让每次渲染都重连，
 * 而那种「一直在重连」从表面上看和「连着」一模一样。
 */
export function useOpsStream(event: string, onData: Listener, enabled = true) {
  const cb = useRef(onData);
  cb.current = onData;

  useEffect(() => {
    if (!enabled) return;
    const fn: Listener = (d) => cb.current(d);
    if (!listeners.has(event)) listeners.set(event, new Set());
    listeners.get(event)!.add(fn);
    acquire();
    return () => {
      listeners.get(event)?.delete(fn);
      release();
    };
  }, [event, enabled]);
}
