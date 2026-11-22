import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * 失败响应里的 `data` 要随 ApiError 带出去：打印中心把机器可读的原因（如 print.extraCopiesNotAllowed）
 * 放在 data.key，端上据此给出具体文案；丢掉它用户只能看到一句泛话。
 */
let respond: (res: { statusCode: number; data: unknown }) => void = () => {};

beforeEach(() => {
  vi.resetModules();
  vi.stubGlobal("uni", {
    getStorageSync: () => "token",
    removeStorageSync: () => {},
    request: (options: { success: typeof respond }) => { respond = options.success; },
  });
});

describe("ApiError keeps the failure data", () => {
  it("exposes data.key of a failed business response", async () => {
    const { request } = await import("@shared/net/http-client");
    const pending = request("POST", "/biz/print/jobs/J1/extra-copies");
    respond({ statusCode: 422, data: { code: 82001, msg: "打印操作未完成", data: { key: "print.extraCopiesNotAllowed" } } });
    await expect(pending).rejects.toMatchObject({ code: 82001, data: { key: "print.extraCopiesNotAllowed" } });
  });

  it("leaves data undefined when the body carries none", async () => {
    const { request } = await import("@shared/net/http-client");
    const pending = request("GET", "/biz/anything");
    respond({ statusCode: 200, data: { code: 10001, msg: "bad" } });
    await expect(pending).rejects.toMatchObject({ code: 10001, data: undefined });
  });
});
