import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
const mocks = vi.hoisted(() => ({ phone: vi.fn(), login: vi.fn(), cart: vi.fn(), active: vi.fn(), addresses: vi.fn(), add: vi.fn() }));
vi.mock("@/api", () => ({ api: { loginByWxPhone: mocks.phone, login: mocks.login, cartList: mocks.cart, activeAddress: mocks.active, addressList: mocks.addresses, cartAdd: mocks.add } }));
vi.mock("@shared/ports/push", () => ({ getPushDevice: async () => null }));
import { useUserStore } from "@/stores/user";
import { useCartStore } from "@/stores/cart";
import { useLocationStore } from "@/stores/location";

describe("phone account session", () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks(); });
  it("technical token does not authorize business actions", () => {
    const user = useUserStore(); user.token = "technical"; user.user = { userNo: "guest", phone: "" } as never;
    expect(user.isLogin).toBe(true); expect(user.isPhoneLogin).toBe(false);
    user.token = ""; user.user.phone = "13800138000"; expect(user.isPhoneLogin).toBe(false);
  });
  it("failure leaves the old session and inviter intact", async () => {
    const user = useUserStore(); user.token = "technical"; user.pendingInviter = "invite";
    mocks.phone.mockRejectedValue(new Error("expired"));
    await expect(user.loginByWxPhone("bad")).rejects.toThrow("expired");
    expect(user.token).toBe("technical"); expect(user.pendingInviter).toBe("invite");
  });
  it("accepts canonical account and clears old account caches before ready", async () => {
    const user = useUserStore(); user.token = "technical"; user.user = { userNo: "old", phone: "" } as never;
    user.pendingInviter = "invite"; useCartStore().items = [{ skuNo: "private-old" }] as never;
    useLocationStore().list = [{ addressId: "private-address" }] as never;
    mocks.phone.mockResolvedValue({ token: "formal", user: { userNo: "owner", phone: "13800138000" } });
    expect(await user.loginByWxPhone("phone-code")).toEqual({ userChanged: true });
    expect(mocks.phone).toHaveBeenCalledWith({ phoneCode: "phone-code", inviterNo: "invite", agreed: true });
    expect(user.token).toBe("formal"); expect(user.isPhoneLogin).toBe(true); expect(user.pendingInviter).toBe("");
    expect(useCartStore().items).toEqual([]); expect(useLocationStore().list).toEqual([]);
  });
  it("old in-flight private requests cannot repopulate caches after an account switch", async () => {
    const user = useUserStore(); user.token = "technical"; user.user = { userNo: "old", phone: "" } as never;
    let finishCart!: (value: unknown) => void;
    let finishAddresses!: (value: unknown) => void;
    mocks.cart.mockReturnValue(new Promise(resolve => { finishCart = resolve; }));
    mocks.active.mockResolvedValue(null);
    mocks.addresses.mockReturnValue(new Promise(resolve => { finishAddresses = resolve; }));
    const oldCart = useCartStore().load();
    const oldLocations = useLocationStore().load();
    mocks.phone.mockResolvedValue({ token: "formal", user: { userNo: "owner", phone: "13800138000" } });
    await user.loginByWxPhone("phone-code");
    finishCart([{ skuNo: "private-old" }]); finishAddresses([{ addressId: "private-old" }]);
    await Promise.all([oldCart, oldLocations]);
    expect(useCartStore().items).toEqual([]); expect(useLocationStore().list).toEqual([]);
  });
  it("silent identity login preserves inviter until phone registration", async () => {
    const user = useUserStore(); user.pendingInviter = "invite";
    mocks.login.mockResolvedValue({ token: "technical", user: { userNo: "guest", phone: "" } });
    await user.login({ grantType: "WX_MINI", principal: "wx-code" });
    expect(user.pendingInviter).toBe("invite");
  });  it("a new wx session cannot silently switch away from the last verified phone account", async () => {
    const user = useUserStore(); user.token = "technical"; user.user = { userNo: "wx-owner", phone: "13800138000" } as never;
    mocks.phone.mockResolvedValue({ token: "formal", user: { userNo: "phone-owner", phone: "13900139000" }, wxRelation: "pending" });
    await user.loginByWxPhone("phone-code"); expect(user.isPhoneLogin).toBe(true);
    user.clearSession();
    mocks.login.mockResolvedValue({ token: "technical-new", user: { userNo: "wx-owner", phone: "13800138000" } });
    await user.login({ grantType: "WX_MINI", principal: "wx-code" });
    expect(user.isLogin).toBe(true); expect(user.isPhoneLogin).toBe(false);
  });
  it("explicit logout stays a guest even when wx.login recognizes the old phone account", async () => {
    const user = useUserStore(); user.token = "formal"; user.user = { userNo: "owner", phone: "13800138000" } as never;
    await user.logout();
    mocks.login.mockResolvedValue({ token: "technical", user: { userNo: "owner", phone: "13800138000" } });
    await user.login({ grantType: "WX_MINI", principal: "wx-code" });
    expect(user.isLogin).toBe(true); expect(user.isPhoneLogin).toBe(false);
  });
  it("an old cart mutation cannot write its response into the new phone account", async () => {
    const user = useUserStore(); user.token = "old"; user.user = { userNo: "old", phone: "" } as never;
    let finish!: (value: unknown) => void;
    mocks.add.mockReturnValue(new Promise(resolve => { finish = resolve; }));
    const pending = useCartStore().add("G1", "S1");
    mocks.phone.mockResolvedValue({ token: "formal", user: { userNo: "owner", phone: "13800138000" } });
    await user.loginByWxPhone("phone-code");
    finish([{ skuNo: "private-old" }]);
    await expect(pending).rejects.toThrow(); expect(useCartStore().items).toEqual([]);
  });

});
