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
