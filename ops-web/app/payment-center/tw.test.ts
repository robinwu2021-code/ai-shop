import { describe, expect, it, vi } from "vitest";
import { createPayOpsClient } from "./_api/client";
import { createHttpPayCenterApi } from "./_api/api";
import { TW_CHANNELS, alertsByChannel, successRate } from "./tw-health";

function httpApi(data: unknown = { records: [], total: 0 }) {
  const f = vi.fn(async (_u: string, _i?: RequestInit) => new Response(JSON.stringify({ code: 0, message: "OK", data, requestId: "r" }), { status: 200 }));
  return { f, api: createHttpPayCenterApi(createPayOpsClient({ baseUrl: "/pay", fetcher: f as unknown as typeof fetch })) };
}

describe("台湾主流渠道运营（《支付中心-台湾主流渠道运营需求》）", () => {
  it("管理范围是蓝新、LINE Pay、街口三家", () => {
    expect([...TW_CHANNELS]).toEqual(["NEWEBPAY", "LINEPAY", "JKOPAY"]);
  });

  it("成功率：没有调用时不算 0%，保留一位小数", () => {
    expect(successRate(0, 0)).toBeNull();
    expect(successRate(97, 100)).toBe(97);
    expect(successRate(2, 3)).toBe(66.7);
  });

  it("未处理告警只按渠道范围归类，实例级告警不算进任何渠道", () => {
    expect(alertsByChannel([
      { scopeType: "CHANNEL", scopeRef: "NEWEBPAY" }, { scopeType: "CHANNEL", scopeRef: "NEWEBPAY" },
      { scopeType: "CHANNEL", scopeRef: "LINEPAY" }, { scopeType: "INSTANCE", scopeRef: null },
    ])).toEqual({ NEWEBPAY: 2, LINEPAY: 1 });
  });

  it("订单按渠道筛选走 near-pay 的 payChannel 参数；健康卡片的调用统计带渠道、时间与状态", async () => {
    const { f, api } = httpApi();
    await api.orders.list({ page: 1, size: 20, payChannel: "JKOPAY" });
    await api.traces.calls({ payChannel: "NEWEBPAY", from: 1, status: "SUCCEEDED", page: 1, size: 1 });
    await api.credentials.list({ credentialType: "LINEPAY_MERCHANT_SIGN", expiringWithinDays: 30, page: 1, size: 1 });
    expect(String(f.mock.calls[0][0])).toBe("/pay/orders?page=1&size=20&payChannel=JKOPAY");
    expect(String(f.mock.calls[1][0])).toBe("/pay/channel-calls?payChannel=NEWEBPAY&from=1&status=SUCCEEDED&page=1&size=1");
    expect(String(f.mock.calls[2][0])).toBe("/pay/credentials?credentialType=LINEPAY_MERCHANT_SIGN&expiringWithinDays=30&page=1&size=1");
  });
});
