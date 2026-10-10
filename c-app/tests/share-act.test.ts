/**
 * 分享入口组件（TDD-C 端裂变与商家招募 §3.2）。
 *
 * <p>守两件事：
 * <ol>
 *   <li><b>两端都有出口</b>。此前商品页那颗按钮挂在 `canNativeShare()` 上，
 *       而它只在微信小程序为 true —— H5（`/c/`）上三个页面一个分享入口都没有，
 *       而 H5 没有胶囊菜单可以兜底。</li>
 *   <li><b>复制的链接带归因</b>。复制一个光秃秃的链接等于把归因丢了，
 *       而丢了之后没有任何症状：人照样进来，只是算不到分享他的那个人头上，
 *       邀请台账也不会多一行。</li>
 * </ol>
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";

const nativeShare = { yes: false };
const clipboard = { data: "" };

vi.mock("@shared/ports/share", async () => {
  const actual = await vi.importActual<typeof import("@shared/ports/share")>("@shared/ports/share");
  return { ...actual, canNativeShare: () => nativeShare.yes };
});
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));

import ShareAct from "@/components/biz/biz-share-act.vue";

function render(props: Record<string, unknown> = {}) {
  return mount(ShareAct, {
    props: { path: "/pages/goods/index?goodsNo=G1", ...props },
    global: { stubs: { "sh-icon": true }, mocks: { $t: (k: string) => k } },
  });
}

describe("分享入口", () => {
  beforeEach(() => {
    nativeShare.yes = false;
    clipboard.data = "";
    (globalThis.uni as unknown as Record<string, unknown>).setClipboardData =
      (o: { data: string; success?: () => void }) => {
        clipboard.data = o.data;
        o.success?.();
      };
    (globalThis.uni as unknown as Record<string, unknown>).showToast = vi.fn();
  });

  it("★★★ H5 上入口照样在，且不是一颗点了没反应的原生按钮", () => {
    const w = render();
    expect(w.find(".shareact").exists()).toBe(true);
    expect(w.find(".shareact__native").exists()).toBe(false);
    expect(w.html()).toContain("share.copy");
  });

  it("★★★ H5 复制的链接**带归因** —— 丢了它，人照样进来却算不到任何人头上", async () => {
    const w = render({ inviterNo: "U-ME", merchantNo: "M1" });
    await w.find(".shareact").trigger("tap");
    expect(clipboard.data).toContain("inviterNo=U-ME");
    expect(clipboard.data).toContain("merchantNo=M1");
    expect(clipboard.data).toContain("/pages/goods/index?goodsNo=G1");
  });

  it("★★ 小程序上是原生转发按钮，不走复制", async () => {
    nativeShare.yes = true;
    const w = render({ inviterNo: "U-ME" });
    expect(w.find(".shareact__native").attributes("open-type")).toBe("share");
    await w.find(".shareact").trigger("tap");
    expect(clipboard.data, "小程序上不该去碰剪贴板 —— 转发由微信接管").toBe("");
  });

  it("★★ 没有归因参数时也能分享 —— 未登录的人分享商品是正常的", async () => {
    const w = render();
    await w.find(".shareact").trigger("tap");
    expect(clipboard.data).toBe("/pages/goods/index?goodsNo=G1");
  });
});

/**
 * 分享到朋友圈（`onShareTimeline`）。
 *
 * <p><b>形状与转发给好友不是一回事</b>：朋友圈落的是「单页模式」，微信只接受 `query`——
 * 给 `path` 会被忽略。直接把 `buildShareMessage` 的结果返回给 `onShareTimeline`，
 * 归因参数会**跟着那个被忽略的 path 一起没掉**：人从朋友圈进来了，却算不到任何人头上，
 * 而页面上一切正常。
 */
describe("分享到朋友圈", () => {
  it("★★★ 归因拼进 query，不靠 path", async () => {
    const { buildShareTimeline } = await import("@shared/ports/share");
    const r = buildShareTimeline({
      title: "柠檬",
      path: "/pages/goods/index",
      params: "goodsNo=G1",
      inviterNo: "U-ME",
      merchantNo: "M1",
    });
    expect(r.query).toContain("goodsNo=G1");
    expect(r.query).toContain("inviterNo=U-ME");
    expect(r.query).toContain("merchantNo=M1");
    expect(r, "朋友圈不吃 path —— 给了只会让人以为归因带上了").not.toHaveProperty("path");
  });

  it("★★★ 三个页面都注册了 onShareTimeline —— 没有它那一栏是灰的，点不了", () => {
    const fs = require("node:fs"), path = require("node:path");
    for (const page of ["goods", "store", "merchant"]) {
      const src = fs.readFileSync(
        path.resolve(__dirname, `../src/pages/${page}/index.vue`), "utf8");
      expect(src, `${page} 页不能分享到朋友圈`).toContain("onShareTimeline(");
      expect(src).toContain("buildShareTimeline(");
    }
  });
});
