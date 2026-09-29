/**
 * 朋友圈分享的三处修补（TDD-C端朋友圈分享修补）。
 *
 * - AC1 海报「分享到朋友圈」弹微信原生图片菜单；老版本微信退回保存；**点取消不是失败**
 * - AC2 朋友圈卡片的配图只放行 http(s)（种子里的 emoji 图交给微信只会退回默认图）
 * - AC3 从朋友圈卡片打开的单页模式（场景值 1154）：跳转被微信禁掉且不报错 ——
 *       壳上拦下并明说；加购在请求之前就拦；落地页顶上一条提示
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { uniMock } from "./setup";
import {
  inTimelineSinglePage,
  shareImageUrl,
  showShareImage,
  TIMELINE_SINGLE_PAGE_SCENE,
} from "@shared/ports/share";
import { guardSinglePageNavigation } from "@/shared/single-page";
import SinglePageTip from "@/components/biz/biz-single-page-tip.vue";

const cartAdd = vi.fn();
vi.mock("@/api", () => ({ api: { cartAdd: (...a: unknown[]) => cartAdd(...a) } }));

import { useCartStore } from "@/stores/cart";

type UniExtra = {
  getEnterOptionsSync?: () => { scene: number };
  addInterceptor?: (name: string, o: { invoke: () => boolean }) => void;
  showShareImageMenu?: (o: { path: string; fail?: (e: unknown) => void }) => void;
  saveImageToPhotosAlbum?: unknown;
};
const u = uniMock as typeof uniMock & UniExtra;

function enterScene(scene: number) {
  u.getEnterOptionsSync = () => ({ scene });
}

afterEach(() => {
  delete u.getEnterOptionsSync;
  delete u.addInterceptor;
  delete u.showShareImageMenu;
  vi.clearAllMocks();
});

describe("AC3 朋友圈单页模式", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("★★★ 场景值 1154 才算单页模式；拿不到进入场景时不算", () => {
    enterScene(TIMELINE_SINGLE_PAGE_SCENE);
    expect(inTimelineSinglePage()).toBe(true);
    enterScene(1001);
    expect(inTimelineSinglePage()).toBe(false);
    u.getEnterOptionsSync = () => {
      throw new Error("not supported");
    };
    expect(inTimelineSinglePage()).toBe(false);
  });

  it("★★★ 单页模式下四个跳转接口都挂上拦截：弹提示并取消这次调用", () => {
    enterScene(TIMELINE_SINGLE_PAGE_SCENE);
    const hooked: Record<string, { invoke: () => boolean }> = {};
    u.addInterceptor = vi.fn((name: string, o: { invoke: () => boolean }) => {
      hooked[name] = o;
    });
    expect(guardSinglePageNavigation(() => "去底部")).toBe(true);
    expect(Object.keys(hooked).sort()).toEqual(["navigateTo", "reLaunch", "redirectTo", "switchTab"]);
    expect(hooked.navigateTo!.invoke(), "返回 false 才是取消").toBe(false);
    expect(uniMock.showToast).toHaveBeenCalledWith(expect.objectContaining({ title: "去底部" }));
  });

  it("★★ 不是单页模式时一个拦截都不挂 —— 否则整个小程序都跳不了页", () => {
    enterScene(1001);
    u.addInterceptor = vi.fn();
    expect(guardSinglePageNavigation(() => "x")).toBe(false);
    expect(u.addInterceptor).not.toHaveBeenCalled();
  });

  it("★★★ 单页模式下加购在请求之前就拦，并带着能 toast 的说明", async () => {
    enterScene(TIMELINE_SINGLE_PAGE_SCENE);
    u.addInterceptor = vi.fn();
    guardSinglePageNavigation(() => "点底部前往小程序");
    await expect(useCartStore().add("G1", "S1", 1)).rejects.toThrow("点底部前往小程序");
    expect(cartAdd).not.toHaveBeenCalled();

    enterScene(1001);
    cartAdd.mockResolvedValue([]);
    await useCartStore().add("G1", "S1", 1);
    expect(cartAdd).toHaveBeenCalledOnce();
  });

  it("★★ 落地页顶上的提示只在单页模式出现", () => {
    const opts = { global: { mocks: { $t: (k: string) => k } } };
    enterScene(TIMELINE_SINGLE_PAGE_SCENE);
    expect(mount(SinglePageTip, opts).text()).toContain("share.singlePageTip");
    enterScene(1001);
    expect(mount(SinglePageTip, opts).text()).toBe("");
  });
});

describe("AC1 海报发朋友圈", () => {
  it("★★★ 有原生图片菜单就弹它，不去存相册", () => {
    u.showShareImageMenu = vi.fn();
    const save = vi.fn();
    expect(showShareImage("/tmp/p.png", save)).toBe(true);
    expect(u.showShareImageMenu).toHaveBeenCalledWith(expect.objectContaining({ path: "/tmp/p.png" }));
    expect(save).not.toHaveBeenCalled();
  });

  it("★★ 用户在菜单里点取消不是失败，不替他存图", () => {
    u.showShareImageMenu = vi.fn((o) => o.fail?.({ errMsg: "showShareImageMenu:fail cancel" }));
    const save = vi.fn();
    showShareImage("/tmp/p.png", save);
    expect(save).not.toHaveBeenCalled();
  });

  it("★★★ 老版本微信没有这个接口：退回保存到相册", () => {
    const save = vi.fn();
    expect(showShareImage("/tmp/p.png", save)).toBe(false);
    expect(save).toHaveBeenCalledOnce();
  });
});

describe("AC2 朋友圈卡片配图", () => {
  it("★★★ 只放行 http(s)，按顺序取第一个能用的", () => {
    expect(shareImageUrl("🍐", "https://img.hxmall.top/a.jpg!w375")).toBe("https://img.hxmall.top/a.jpg!w375");
    expect(shareImageUrl("http://x/a.png")).toBe("http://x/a.png");
    expect(shareImageUrl("🍐", "", null, undefined)).toBeUndefined();
  });
});
