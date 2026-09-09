/**
 * 「失焦时失败的查询卡死在 pending」的回归测试。
 *
 * 这不是一条假想的规则 —— 2026-09-09 在 `/communities?tab=health` 上实地撞到：
 * 后端返回错误（那次是本地 jar 陈了，`AbstractMethodError`），
 * 而页面显示的是**骨架屏**，不是错误态。查下去发现 React Query 的状态是
 * `status: "pending" / fetchStatus: "paused"` —— 请求失败过、重试被暂停了，
 * 于是 `if (!data && isPending)` 那一支永远成立。
 *
 * 两条断言是一对，缺一不可：
 *   · 第一条**证明这个坑真的存在**（去掉 providers.tsx 里那段钉焦点的代码，
 *     线上就是这个行为）。它断言的是「坏的那一半」，所以看起来很怪 ——
 *     但没有它，第二条就只是「现在是好的」，说明不了为什么要有那段代码。
 *   · 第二条证明修法有效。
 */
import { describe, it, expect, beforeEach } from "vitest";
import { QueryClient, focusManager, onlineManager } from "@tanstack/react-query";

/** 与 components/providers.tsx 保持一致；改那边要改这里 */
const mkClient = () =>
  new QueryClient({
    defaultOptions: { queries: { staleTime: 15_000, retry: 1, refetchOnWindowFocus: false } },
  });

const boom = () => Promise.reject(new Error("boom"));
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

beforeEach(() => {
  onlineManager.setOnline(true);
});

describe("失焦时失败的查询", () => {
  it("不钉焦点：卡在 pending/paused，且焦点回来也不恢复 —— 页面会一直显示骨架屏", async () => {
    focusManager.setEventListener(() => () => {});   // 先接管，免得被真实事件干扰
    focusManager.setFocused(false);
    const qc = mkClient();
    void qc.fetchQuery({ queryKey: ["stuck"], queryFn: boom }).catch(() => {});
    // 等过 retry 的退避窗口（默认约 1s）—— 暂停发生在「要重试的那一刻」，不是失败当时
    await wait(1600);
    const paused = qc.getQueryCache().find({ queryKey: ["stuck"] })!.state;
    expect([paused.status, paused.fetchStatus]).toEqual(["pending", "paused"]);

    // 焦点回来 —— 直觉上该继续重试，实测不会
    focusManager.setFocused(true);
    await wait(1200);
    const after = qc.getQueryCache().find({ queryKey: ["stuck"] })!.state;
    expect([after.status, after.fetchStatus]).toEqual(["pending", "paused"]);
  });

  it("钉住焦点（providers.tsx 的做法）：失败落到 error，页面能出错误态与重试", async () => {
    focusManager.setEventListener(() => () => {});
    focusManager.setFocused(true);
    const qc = mkClient();
    void qc.fetchQuery({ queryKey: ["surfaced"], queryFn: boom }).catch(() => {});
    await wait(1500);
    const s = qc.getQueryCache().find({ queryKey: ["surfaced"] })!.state;
    expect([s.status, s.fetchStatus]).toEqual(["error", "idle"]);
  });
});
