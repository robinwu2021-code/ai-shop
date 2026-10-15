import { STORAGE } from "@shared/utils/constants";
import { beforeEach, describe, expect, it, vi } from "vitest";
let token = "old";
let respond: (value: unknown) => void;
const remove = vi.fn(() => { token = ""; });
beforeEach(() => {
  vi.resetModules(); vi.clearAllMocks(); token = "old";
  (globalThis as Record<string, unknown>).uni = {
    getStorageSync: (key: string) => key === STORAGE.token ? token : "",
    removeStorageSync: remove,
    request: (options: { success: typeof respond }) => { respond = options.success; },
  };
});
describe("401 belongs to the request token", () => {
  it("late old-token 401 cannot clear the new session or run recovery", async () => {
    const { request, setUnauthorizedHandler } = await import("@shared/net/http-client");
    const handler = vi.fn(); setUnauthorizedHandler(handler);
    const pending = request("GET", "/mp/user/profile"); token = "new";
    respond({ statusCode: 401, data: { code: 401 } });
    await expect(pending).rejects.toMatchObject({ code: 401 });
    expect(token).toBe("new"); expect(remove).not.toHaveBeenCalled(); expect(handler).not.toHaveBeenCalled();
  });
  it("passes failed request token to recovery and keeps zero-argument handlers compatible", async () => {
    const { request, setUnauthorizedHandler } = await import("@shared/net/http-client");
    const handler = vi.fn(); setUnauthorizedHandler(handler);
    const pending = request("GET", "/mp/user/profile");
    respond({ statusCode: 401, data: { code: 401 } }); await expect(pending).rejects.toMatchObject({ code: 401 });
    expect(handler).toHaveBeenCalledWith({ requestToken: "old" }); expect(remove).toHaveBeenCalledTimes(1);
  });
});
