/**
 * 请求参数里的 `undefined` 不能变成字面量 `"undefined"`。
 *
 * <p>`uni.request` 在 GET 时把 `data` 拼进查询串，而 `undefined` 会被拼成字符串。
 * 未绑社区的游客于是请求 `/mp/merchant?communityNo=undefined`，
 * 后端把它当成一个真实社区去过滤 —— **返回 1 家而不是 6 家**。
 * 不报错、不空白，只是少了五家店，没人会怀疑一个「没传的参数」。
 *
 * <p>抓到它靠的是生产 nginx 的访问日志（小程序真机链路），
 * 三端的类型检查、契约守卫、接口 curl 全都看不出来 —— curl 时那个参数压根不存在。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";

const sent: Record<string, unknown>[] = [];

beforeEach(() => {
  sent.length = 0;
  vi.resetModules();
  (globalThis as Record<string, unknown>).uni = {
    getStorageSync: () => "",
    removeStorageSync: () => {},
    request: (opt: Record<string, unknown>) => {
      sent.push(opt);
      (opt.success as (r: unknown) => void)({
        statusCode: 200,
        data: { code: 0, msg: "ok", data: null },
      });
    },
  };
});

async function get(params: object) {
  const { request } = await import("@shared/net/http-client");
  await request("GET", "/mp/merchant", params);
  return sent[0].data as Record<string, unknown>;
}

describe("请求参数序列化", () => {
  it("★★★ undefined 的字段直接不发 —— 发出去会变成字符串 \"undefined\"", async () => {
    const data = await get({ communityNo: undefined, page: 1 });
    expect(Object.keys(data)).not.toContain("communityNo");
    expect(data).toEqual({ page: 1 });
  });

  it("★★ null 同理", async () => {
    expect(await get({ communityNo: null, keyword: "米" })).toEqual({ keyword: "米" });
  });

  it("空串与 0 要保留 —— 它们是有意义的值，不是「没传」", async () => {
    expect(await get({ keyword: "", page: 0 })).toEqual({ keyword: "", page: 0 });
  });

  it("数组原样透传，不被当成对象拆开", async () => {
    const { request } = await import("@shared/net/http-client");
    await request("POST", "/mp/x", ["a", "b"] as unknown as object);
    expect(sent[0].data).toEqual(["a", "b"]);
  });
});
