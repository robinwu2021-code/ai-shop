// 服务端菜单 store 的三条语义 —— 它们都是「失败时会怎样」，而不是「成功时怎样」。
//
// 这个 store 决定运营看到哪些菜单、哪些灰显，所以**它坏掉的样子**比它工作的样子更值得测：
// 接口抖一下就把菜单全藏起来，用户看到的是「系统坏了」，而实际上只是一次超时。
import { beforeEach, describe, expect, it, vi } from "vitest";
import { isPointUnimplemented, useServerMenu } from "./server-menu";
import type { MenuPoint } from "@/lib/types";

const point = (over: Partial<MenuPoint>): MenuPoint => ({
  pointCode: "P1", name: "x", href: "/x", backendStatus: "IMPLEMENTED",
  uiReady: true, pointType: "MENU", sort: 10, ...over,
});

vi.mock("@/lib/api", () => ({ api: { menu: vi.fn() } }));
const { api } = await import("@/lib/api");

describe("服务端菜单 store", () => {
  beforeEach(() => {
    useServerMenu.getState().clear();
    vi.mocked(api.menu).mockReset();
  });

  it("★★ 按 href 建索引，ACTION 类型不进导航", async () => {
    vi.mocked(api.menu).mockResolvedValue([
      { functionCode: "F", name: "f", sort: 10, points: [
        point({ href: "/a" }),
        // 页面内的按钮级授权：塞进导航会多出几十行看不懂的项
        point({ href: "/b", pointType: "ACTION" }),
      ] },
    ] as never);
    await useServerMenu.getState().load();
    const { byHref, loaded } = useServerMenu.getState();
    expect(Object.keys(byHref)).toEqual(["/a"]);
    expect(loaded).toBe(true);
  });

  it("★★★ 接口失败时 loaded 保持 false —— 回落到静态 nav，而不是空菜单", async () => {
    vi.mocked(api.menu).mockRejectedValue(new Error("timeout"));
    await useServerMenu.getState().load();
    expect(useServerMenu.getState().loaded)
      .toBe(false);
  });

  it("★★★ 失败不清空已有结果 —— 宁可用上一次的，也不要突然变成空菜单", async () => {
    vi.mocked(api.menu).mockResolvedValue([
      { functionCode: "F", name: "f", sort: 10, points: [point({ href: "/a" })] },
    ] as never);
    await useServerMenu.getState().load();
    vi.mocked(api.menu).mockRejectedValue(new Error("boom"));
    await useServerMenu.getState().load();
    expect(useServerMenu.getState().byHref["/a"]).toBeTruthy();
  });

  it("★★ 未实现只认 NOT_IMPLEMENTED；查不到的项按「已实现」处理（回落）", async () => {
    const byHref = {
      "/impl": point({ href: "/impl" }),
      "/todo": point({ href: "/todo", backendStatus: "NOT_IMPLEMENTED" }),
    };
    expect(isPointUnimplemented(byHref, "/todo")).toBe(true);
    expect(isPointUnimplemented(byHref, "/impl")).toBe(false);
    // 菜单还没加载完时不该把所有项都当成未实现 —— 那会让整个导航灰掉
    expect(isPointUnimplemented({}, "/impl")).toBe(false);
  });
});
