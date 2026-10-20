import { beforeEach, describe, expect, it, vi } from "vitest";
const user = vi.hoisted(() => ({ token: "old", silentLogin: vi.fn() }));
vi.mock("@/stores/user", () => ({ useUserStore: () => user }));
import { readStoredValueWithSession } from "@/shared/stored-value-session";
import { uniMock } from "./setup";
beforeEach(() => { vi.clearAllMocks(); user.token = "old"; });
describe("储值通知登录恢复", () => {
  it("401等候现有静默登录后携同一focus读取，最多重读一次", async () => {
    const query = { storeNo: "S1", focusDocumentNo: "EU1" };
    const fetch = vi.fn().mockRejectedValueOnce({ code: 401 }).mockResolvedValueOnce({ documentNo: "EU1" });
    user.silentLogin.mockImplementation(async () => { user.token = "restored"; return true; });
    const read = () => fetch(query);
    expect(await readStoredValueWithSession(read, () => "/pages/stored-value-records/index?storeNo=S1&focusDocumentNo=EU1")).toEqual({ documentNo: "EU1" });
    expect(fetch).toHaveBeenCalledTimes(2); expect(fetch.mock.calls[1]).toEqual([query]);
    expect(uniMock.navigateTo).not.toHaveBeenCalled();
  });
  it("不能恢复时跳已有登录页并保存完整通知落点", async () => {
    const path = "/pages/stored-value-records/index?storeNo=S2&focusDocumentNo=EU2";
    user.silentLogin.mockResolvedValue(false);
    const read = vi.fn().mockRejectedValue({ code: 401 });
    await expect(readStoredValueWithSession(read, () => path)).rejects.toEqual({ code: 401 });
    expect(read).toHaveBeenCalledTimes(1);
    expect(uniMock.navigateTo).toHaveBeenCalledWith(expect.objectContaining({ url: `/pages/login/index?redirect=${encodeURIComponent(path)}` }));
  });
  it("恢复后仍401仅重读一次，其他错误不重新登录", async () => {
    user.silentLogin.mockResolvedValue(true);
    const read = vi.fn().mockRejectedValue({ code: 401 });
    await expect(readStoredValueWithSession(read, () => "/pages/stored-value/index")).rejects.toEqual({ code: 401 });
    expect(read).toHaveBeenCalledTimes(2);
    user.silentLogin.mockClear();
    await expect(readStoredValueWithSession(async () => { throw { code: 10404 }; }, () => "unused")).rejects.toEqual({ code: 10404 });
    expect(user.silentLogin).not.toHaveBeenCalled();
  });
});
