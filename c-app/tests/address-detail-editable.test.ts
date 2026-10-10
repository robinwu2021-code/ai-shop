// 没有坐标时，详细地址必须能填。
//
// 2026-10-10 用户上报「新增地址后不能编辑和修改」。
// 那个输入框挂着 `:disabled="canPick"`，而小程序上 canPick 恒为真 ——
// 于是「新增地址」里详细地址填不了，存量那些没有坐标的地址（微信导入、粘贴识别、手填）
// 也永远改不了。原注释的理由「地址主体跟坐标一起来的，手改文字坐标不跟着动」是对的，
// 但它**只在有坐标时成立**，而有坐标走的是另一支（只读卡片）——
// 这个输入框所在的 `v-else` 恰恰是「没有坐标」，那里没有坐标可以跟文字不一致。
// 锁反了：锁在唯一需要手填的场景上。
//
// 这条用例断的是**性质**不是写法：上一版守卫断的是字面量 `:disabled="canPick"`，
// 所以它挡不住「锁反了」，只挡得住「有没有这串字」。
import { describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createI18n } from "vue-i18n";

// 端能力全开 = 小程序那一侧（canPick / canWx 都为真），正是出问题的那一端
vi.mock("@shared/ports/location", () => ({
  canChooseLocation: () => true,
  canChooseWxAddress: () => true,
  canSearchPlaces: () => true,
  chooseLocation: () => Promise.resolve({ ok: false }),
  chooseWxAddress: () => Promise.resolve({ ok: false }),
}));

async function form(props: Record<string, unknown>) {
  setActivePinia(createPinia());
  const mod = await import("@/components/biz/biz-address-form.vue");
  return mount(mod.default, {
    props,
    global: {
      // 组件 setup 里调了 useI18n()，必须装真插件（只 mock $t 挡不住它）。
      // messages 留空：t(key) 原样返回 key，正好用来按 placeholder 认那一栏。
      plugins: [createI18n({ legacy: false, locale: "zh-CN", messages: { "zh-CN": {} } })],
      stubs: { "sh-sheet": true, "sh-picker": true },
    },
  });
}

/** 详细地址那一栏：placeholder 指向 address.detail */
function detailInput(w: ReturnType<typeof mount> extends Promise<infer _> ? never : any) {
  return w.findAll("input").find((i: any) => i.attributes("placeholder") === "address.detail");
}

describe("详细地址能不能填", () => {
  it("★★★ 没有坐标时必须可输入 —— 小程序上这是唯一能手填的地方", async () => {
    const w = await form({ address: null, place: null, first: false });
    const el = detailInput(w);
    expect(el, "没有坐标时该渲染出详细地址输入框").toBeTruthy();
    expect(el!.attributes("disabled"),
      "撤掉修复这里会是 disabled —— 小程序上详细地址就永远填不了")
      .toBeUndefined();
  });

  it("★★★ 能选点的端、没坐标：顶部是地图卡片，不再有省市区手填框", async () => {
    // 端能力全开 = 小程序那一侧（见文件顶部 mock），canPick 为真
    const w = await form({ address: null, place: null, first: false });
    expect(w.find(".mapcard").exists(), "该用地图卡片代替省市区手填框").toBe(true);
    const region = w.findAll("input").find((i: any) => i.attributes("placeholder") === "address.region");
    expect(region, "能选点时省市区不再手填 —— 撤掉重构这里会冒出 region 输入框").toBeFalsy();
  });

  it("★★★ 编辑一条没坐标的旧地址：卡片显示它原有的地名", async () => {
    const w = await form({
      address: { addressId: "A9", region: "广东省深圳市龙华区", detail: "某路1号", name: "张三", phone: "13800000000" },
      place: null, first: false,
    });
    expect(w.find(".mapcard").text(), "编辑旧地址时卡片要带出原地名，不能只显示空的引导语")
      .toContain("广东省深圳市龙华区");
  });

  it("★★★ 有坐标时不渲染这个输入框 —— 那一支走只读卡片，文字与坐标才不会各说各的", async () => {
    const w = await form({
      address: null,
      place: { kind: "place", name: "某地", region: "广东省深圳市龙华区", detail: "某路 1 号",
        province: "广东省", city: "深圳市", district: "龙华区", latE6: 22655148, lngE6: 114034872 },
      first: false,
    });
    expect(detailInput(w), "有坐标时地址主体是只读的，不该还有可编辑的输入框").toBeFalsy();
  });
});
