import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
const mocks = vi.hoisted(() => ({ phone: vi.fn(), prepare: vi.fn() }));
vi.mock("@/ports/phone-login", () => ({ nativePhoneAuthorization: true }));
vi.mock("@/api", () => ({ api: { loginByWxPhone: mocks.phone } }));
vi.mock("@shared/ports/push", () => ({ getPushDevice: async () => null }));
import Button from "@/components/biz/biz-phone-login-button.vue";
import { onHide, onShow } from "@dcloudio/uni-app";
import { useUserStore } from "@/stores/user";

describe("native phone login button", () => {
  beforeEach(() => {
    setActivePinia(createPinia()); vi.clearAllMocks();
    const user = useUserStore(); user.token = "technical"; user.user = { userNo: "guest", phone: "" } as never;
    mocks.phone.mockResolvedValue({ token: "formal", user: { userNo: "guest", phone: "13800138000" } });
  });
  const mountButton = () => mount(Button, { props: { label: "手机号快捷登录" } });
  it("guest uses getPhoneNumber and tap does not submit or fake authorization", async () => {
    const wrapper = mountButton(); await flushPromises();
    expect(wrapper.get("button").attributes("open-type")).toBe("getPhoneNumber");
    await wrapper.get("button").trigger("tap"); expect(mocks.phone).not.toHaveBeenCalled();
    await wrapper.get("button").trigger("getphonenumber", { detail: { errMsg: "getPhoneNumber:fail user deny" } });
    expect(mocks.phone).not.toHaveBeenCalled(); expect(wrapper.emitted("cancel")).toHaveLength(1);
    wrapper.unmount();
  });
  it("valid session uses ordinary tap and continues once", async () => {
    useUserStore().user!.phone = "13800138000";
    const wrapper = mountButton(); await flushPromises();
    expect(wrapper.get("button").attributes("open-type")).toBeUndefined();
    await wrapper.get("button").trigger("tap"); expect(wrapper.emitted("ready")).toEqual([[{ userChanged: false }]]);
    expect(mocks.phone).not.toHaveBeenCalled(); wrapper.unmount();
  });
  it("deduplicates native callbacks and only emits after server success", async () => {
    let finish!: (value: unknown) => void;
    mocks.phone.mockReturnValue(new Promise(resolve => { finish = resolve; }));
    const wrapper = mountButton(); await flushPromises();
    const event = { detail: { code: "phone-one" } };
    await wrapper.get("button").trigger("getphonenumber", event);
    await wrapper.get("button").trigger("getphonenumber", event);
    expect(mocks.phone).toHaveBeenCalledTimes(1); expect(wrapper.emitted("ready")).toBeUndefined();
    finish({ token: "formal", user: { userNo: "guest", phone: "13800138000" } }); await flushPromises();
    expect(wrapper.emitted("ready")).toHaveLength(1); wrapper.unmount();
  });
  it("leaving the page cancels continuation even if response arrives", async () => {
    let finish!: (value: unknown) => void;
    mocks.phone.mockReturnValue(new Promise(resolve => { finish = resolve; }));
    const wrapper = mountButton(); await flushPromises();
    await wrapper.get("button").trigger("getphonenumber", { detail: { code: "phone-one" } }); wrapper.unmount();
    finish({ token: "formal", user: { userNo: "guest", phone: "13800138000" } }); await flushPromises();
    expect(wrapper.emitted("ready")).toBeUndefined();
  });
  it("failure keeps authorization button and retries with a fresh phone code", async () => {
    mocks.phone.mockRejectedValueOnce(new Error("expired"));
    const wrapper = mountButton(); await flushPromises();
    await wrapper.get("button").trigger("getphonenumber", { detail: { code: "expired" } }); await flushPromises();
    expect(wrapper.emitted("ready")).toBeUndefined(); expect(wrapper.emitted("error")).toHaveLength(1);
    await wrapper.get("button").trigger("getphonenumber", { detail: { code: "fresh" } }); await flushPromises();
    expect(mocks.phone.mock.calls[1][0].phoneCode).toBe("fresh"); expect(wrapper.emitted("ready")).toHaveLength(1);
    wrapper.unmount();
  });  it("hide then return cannot revive an earlier authorization continuation", async () => {
    let finish!: (value: unknown) => void;
    mocks.phone.mockReturnValue(new Promise(resolve => { finish = resolve; }));
    const wrapper = mountButton(); await flushPromises();
    await wrapper.get("button").trigger("getphonenumber", { detail: { code: "phone-one" } });
    vi.mocked(onHide).mock.calls.at(-1)![0](); vi.mocked(onShow).mock.calls.at(-1)![0]();
    finish({ token: "formal", user: { userNo: "guest", phone: "13800138000" } }); await flushPromises();
    expect(wrapper.emitted("ready")).toBeUndefined(); wrapper.unmount();
  });
  it("failed technical preparation offers retry before enabling native authorization", async () => {
    const user = useUserStore(); user.token = ""; user.user = null;
    const silent = vi.spyOn(user, "silentLogin").mockResolvedValue(false);
    const wrapper = mountButton(); await flushPromises();
    expect(wrapper.get("button").attributes("open-type")).toBeUndefined();
    silent.mockImplementation(async () => { user.token = "technical"; return true; });
    await wrapper.get("button").trigger("tap"); await flushPromises();
    expect(wrapper.get("button").attributes("open-type")).toBe("getPhoneNumber");
    expect(mocks.phone).not.toHaveBeenCalled(); wrapper.unmount();
  });

});
