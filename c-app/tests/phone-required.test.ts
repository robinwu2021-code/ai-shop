import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useUserStore } from "@/stores/user";
import {
  withPhone,
  requirePhoneOnEnter,
  onPhoneBound,
  onPhoneGateClose,
  phoneRequired,
} from "@/shared/phone-required";

/**
 * 「这一步要手机号」的统一闸（shared/phone-required）。
 *
 * <p>这道闸管着收藏、关注店铺、参团、进分类页/店铺页/我的页等一串入口。
 * 它要是悄悄失效，表现**不是报错，而是这些动作对没号的人直接放行** ——
 * 走到最后一步（履约要联系买家）才发现没有联系方式。所以每条断言都要能证伪。
 */
describe("要手机号的统一闸", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    onPhoneGateClose(); // 清掉上一条用例可能留下的待办
  });

  /** 有号的人不该被打扰 */
  it("★★★ 已有手机号：直接执行，不弹授权层", async () => {
    const user = useUserStore();
    user.token = "ctk_x";
    user.user = { phone: "13900000000" } as never;
    const action = vi.fn();

    const ran = await withPhone(action);

    expect(ran).toBe(true);
    expect(action).toHaveBeenCalledOnce();
    expect(phoneRequired.visible.value).toBe(false);
  });

  /**
   * **这条是闸的全部意义**：没号时动作必须被拦住。
   * 撤掉 withPhone 里的手机号判断，这条立刻变红。
   */
  it("★★★ 没有手机号：拦住动作并弹授权层", async () => {
    const user = useUserStore();
    user.token = "ctk_x";
    user.user = { phone: "" } as never;
    const action = vi.fn();

    const ran = await withPhone(action);

    expect(ran).toBe(false);
    expect(action).not.toHaveBeenCalled();
    expect(phoneRequired.visible.value).toBe(true);
  });

  /** 绑完要**自动把原来那件事补上** —— 否则用户得自己再点一次，而他多半以为失败了 */
  it("★★★ 绑定成功后自动补上被拦下的动作", async () => {
    const user = useUserStore();
    user.token = "ctk_x";
    user.user = { phone: "" } as never;
    const action = vi.fn();

    await withPhone(action);
    expect(action).not.toHaveBeenCalled();

    await onPhoneBound();

    expect(action).toHaveBeenCalledOnce();
    expect(phoneRequired.visible.value).toBe(false);
  });

  /** 关掉弹层要丢掉待办：留着的话下次绑号时会冒出一个他早就放弃的动作 */
  it("★★ 关掉弹层后，待办不会在下次绑定时冒出来", async () => {
    const user = useUserStore();
    user.token = "ctk_x";
    user.user = { phone: "" } as never;
    const action = vi.fn();

    await withPhone(action);
    onPhoneGateClose();
    await onPhoneBound();

    expect(action).not.toHaveBeenCalled();
  });

  /** 进页即弹：没号就摆出来，有号什么也不做 */
  it("★★ 进页即弹：没号才弹", async () => {
    const user = useUserStore();
    user.token = "ctk_x";
    user.user = { phone: "" } as never;
    await requirePhoneOnEnter();
    expect(phoneRequired.visible.value).toBe(true);

    onPhoneGateClose();
    user.user = { phone: "13900000000" } as never;
    await requirePhoneOnEnter();
    expect(phoneRequired.visible.value).toBe(false);
  });
});
