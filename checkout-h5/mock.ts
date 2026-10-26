import type { Plugin } from "vite";

/**
 * 本机看界面用的假 /checkout/v1（`CHECKOUT_MOCK=1 npm run dev`）。只挂在 dev server 上，不进构建产物。
 * 令牌 demo-cn / demo-tw / demo-ae 三个市场各一张单；expired 回 410；码 QR-DEMO 是一张静态码。
 */
export function checkoutMock(): Plugin {
  const sessions: Record<string, object> = {
    "demo-cn": session("PYO-DEMO-CN", "邻里鲜果", "中山店", 1990, "CNY", 2, "CN", ["WECHAT_PAY", "ALIPAY", "TEST_PAY"]),
    "demo-tw": session("PYO-DEMO-TW", "巷口咖啡", "大安店", 150, "TWD", 0, "TW", ["TEST_PAY"]),
    "demo-ae": session("PYO-DEMO-AE", "متجر الحي", "فرع دبي", 1990, "AED", 2, "AE", ["TEST_PAY"]),
  };
  let paid = false;
  return {
    name: "checkout-mock",
    configureServer(server) {
      server.middlewares.use("/checkout/v1", (req, res) => {
        const url = req.url ?? "";
        const send = (status: number, data: unknown) => {
          res.statusCode = status;
          res.setHeader("Content-Type", "application/json");
          res.end(JSON.stringify({ code: status === 200 ? 0 : 70093, message: status === 200 ? "OK" : "收款链接已失效",
            data: status === 200 ? data : null, requestId: "mock" }));
        };
        const m = url.match(/^\/sessions\/([^/]+)(\/pay|\/result)?$/);
        if (m) {
          const s = sessions[decodeURIComponent(m[1])];
          if (!s) return send(m[1] === "expired" ? 410 : 404, null);
          if (m[2] === "/pay") {
            paid = true;
            return send(200, { payOrderNo: (s as { payOrderNo: string }).payOrderNo, outTradeNo: "T", payChannel: "TEST",
              payParams: { prepayId: "test_mock" } });
          }
          if (m[2] === "/result") return send(200, { result: paid ? "SUCCEEDED" : "PROCESSING" });
          return send(200, s);
        }
        const c = url.match(/^\/codes\/([^/]+)(\/orders)?$/);
        if (c && c[1] === "QR-DEMO") {
          if (c[2]) return send(200, { payOrderNo: "PYO-DEMO-CN", token: "demo-cn", expireAt: Date.now() + 900_000 });
          return send(200, { payeeName: "邻里鲜果", storeName: "中山店", kind: "STORE", tableRef: null, currency: "CNY",
            currencyScale: 2, market: "CN", singleLimitMinor: 5000 });
        }
        return send(404, null);
      });
    },
  };
}

function session(no: string, payee: string, store: string, amount: number, currency: string, scale: number,
                 market: string, methods: string[]) {
  return { payOrderNo: no, payeeName: payee, storeName: store, amountMinor: amount, currency, currencyScale: scale,
    market, status: "OPEN", payMethods: methods, expireAt: Date.now() + 900_000 };
}
