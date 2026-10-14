import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * 输入弹层关闭前必须先收键盘。
 *
 * <p>App 默认的键盘适配是 adjustPan：键盘弹出时整个 webview 上移，收起时移回来。
 * 「确定」是**输入框还聚焦着就被销毁** —— 键盘因为焦点没了而消失，走不到还原那条路，
 * 页面就停在上移状态：标题顶进状态栏、自绘底部菜单停在屏幕中间、下面空一条灰，
 * 切页与返回都救不回来，只能重启 App。
 *
 * <p>2026-09-20 店主在商品列表「改库存 → 确定」之后撞到，真机复现过。
 * 这条用例钉的是**顺序**：hideKeyboard 要在 visible 变 false 之前发生 ——
 * 反过来的话输入框已经没了，收键盘也就不再触发还原。
 */
describe("输入弹层", () => {
  /** 收键盘那一刻弹层还在不在 —— 顺序反了的话输入框已经没了，收键盘不再触发还原 */
  let visibleWhenHidden: boolean | null = null;
  const calls: string[] = [];
  let state: { visible: boolean };

  beforeEach(async () => {
    calls.length = 0;
    visibleWhenHidden = null;
    vi.resetModules();
    (globalThis as unknown as { uni: unknown }).uni = {
      hideKeyboard: () => {
        calls.push("hideKeyboard");
        visibleWhenHidden = state?.visible ?? null;
      },
    };
    state = (await import("@ai-shop/ui/prompt")).promptState;
  });

  it("关闭前先收键盘，再撤弹层", async () => {
    const { prompt, closePrompt, promptState } = await import("@ai-shop/ui/prompt");

    const answer = prompt({ title: "改库存", value: "8" });
    expect(promptState.visible).toBe(true);

    closePrompt("12");

    expect(calls).toEqual(["hideKeyboard"]);
    // **顺序断言**：收键盘时弹层（以及里面的输入框）必须还在
    expect(visibleWhenHidden).toBe(true);
    expect(promptState.visible).toBe(false);
    await expect(answer).resolves.toBe("12");
  });

  it("取消这条路同样收键盘 —— 点「取消」时键盘也开着", async () => {
    const { prompt, closePrompt } = await import("@ai-shop/ui/prompt");
    const answer = prompt({ title: "改库存", value: "8" });
    closePrompt(null);
    expect(calls).toEqual(["hideKeyboard"]);
    await expect(answer).resolves.toBeNull();
  });

  it("没有 uni 运行时也不会抛 —— 收键盘不是这个函数的主业", async () => {
    delete (globalThis as unknown as { uni?: unknown }).uni;
    const { prompt, closePrompt } = await import("@ai-shop/ui/prompt");
    const answer = prompt({ title: "改库存" });
    expect(() => closePrompt("1")).not.toThrow();
    await expect(answer).resolves.toBe("1");
  });
});
