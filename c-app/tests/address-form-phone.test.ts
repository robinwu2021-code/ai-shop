/**
 * 地址表单里的手机号 —— **同一个号不让人输第二遍**。
 *
 * <p>资料接口给本人的是完整号（不脱敏），于是：
 * <ul>
 *   <li>已绑号：新增地址时手机号栏默认就是他的号；</li>
 *   <li>编辑存量地址：不动那条上的号（那是他当时填的，可能是家人）；</li>
 *   <li>从微信地址簿导入：盖掉「我们预填的」那个号，不盖他自己输的；</li>
 *   <li>没绑号：手机号栏下有绑定入口，没绑的人才看得见。</li>
 * </ul>
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";

const chooseWxAddress = vi.fn();

vi.mock("@/api", () => ({ api: { phoneCapable: () => Promise.resolve({ capable: false }) } }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@shared/ports/location", () => ({
  canChooseLocation: () => false,
  canChooseWxAddress: () => true,
  chooseLocation: vi.fn(),
  chooseWxAddress: () => chooseWxAddress(),
}));
vi.mock("@shared/ports/geo-search", () => ({ canSearchPlaces: () => false }));

import BizAddressForm from "@/components/biz/biz-address-form.vue";
import { useUserStore } from "@/stores/user";

function asUser(phone: string) {
  useUserStore().user = { phone } as never;
}

async function render(props: Record<string, unknown> = {}) {
  const w = mount(BizAddressForm, {
    props,
    global: { mocks: { $t: (k: string) => k }, stubs: { "phone-gate": true } },
  });
  await w.vm.$nextTick();
  return w;
}

/** 手机号栏：姓名与手机同一行，第二个输入框 */
const phoneInput = (w: Awaited<ReturnType<typeof render>>) =>
  w.find(".namerow").findAll("input")[1].element as HTMLInputElement;

/** 点「从微信导入」。找不到入口要直接红 —— 不然「没盖掉」那条会因为根本没点而假绿 */
async function fromWx(w: Awaited<ReturnType<typeof render>>) {
  const btn = w.findAll("text").find((n) => n.text() === "address.fromWx");
  expect(btn, "没找到「从微信导入」入口").toBeTruthy();
  await btn!.trigger("tap");
  for (let i = 0; i < 4; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
}

describe("地址表单 · 手机号不重复输入", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it("★★★ 已绑号 → 新增地址时手机号栏默认是他的号", async () => {
    asUser("13800138000");
    const w = await render();
    expect(phoneInput(w).value).toBe("13800138000");
    expect(w.text()).not.toContain("address.bindPhone");
  });

  it("★★ 编辑存量地址 → 不拿本人号盖掉那条上的号", async () => {
    asUser("13800138000");
    const w = await render({
      address: { addressId: "A1", name: "妈妈", phone: "13900139000", region: "", detail: "" },
    });
    expect(phoneInput(w).value).toBe("13900139000");
  });

  it("★★ 没绑号 → 手机号栏空着，下面有绑定入口", async () => {
    asUser("");
    const w = await render();
    expect(phoneInput(w).value).toBe("");
    expect(w.text()).toContain("address.bindPhone");
  });

  it("★★★ 微信地址簿导入 → 盖掉预填的本人号（导入的多半是家人那一条）", async () => {
    asUser("13800138000");
    chooseWxAddress.mockResolvedValue({ name: "妈妈", phone: "13900139000", detail: "1 号楼" });
    const w = await render();
    await fromWx(w);
    expect(phoneInput(w).value).toBe("13900139000");
  });

  it("★ 微信地址簿导入 → 不盖他自己改过的号", async () => {
    asUser("13800138000");
    chooseWxAddress.mockResolvedValue({ name: "妈妈", phone: "13900139000", detail: "1 号楼" });
    const w = await render();
    await w.find(".namerow").findAll("input")[1].setValue("13700137000");
    await fromWx(w);
    expect(phoneInput(w).value).toBe("13700137000");
  });
});
