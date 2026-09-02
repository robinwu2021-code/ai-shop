// 覆盖范围：门店主页治理（P-10.1）。
import { client } from "../http-client";
import type { StoreApi } from "../contracts/store";

export const storeHttp: StoreApi = {
  // 门店档案（P-11.2.1）。**路径顺序敏感**：后端 `/ops/stores/audits` 与
  // `/ops/stores/{storeNo}` 同前缀，`audits`/`qrcodes`/`templates` 是保留段，
  // 门店号不会长这样，所以两边不会打架。
  listStores: (q) => client.get("/ops/stores", q),
  getStore: (storeNo) => client.get(`/ops/stores/${storeNo}`),
  getStoreStats: (storeNo) => client.get(`/ops/stores/${storeNo}/stats`),
  restoreStore: (storeNo) => client.post(`/ops/stores/${storeNo}/restore`),

  listStoreAudits: (q) => client.get("/ops/stores/audits", q),
  decideStoreAudit: (auditNo, pass, reason) => client.post(`/ops/stores/audits/${auditNo}/decide`, { pass, reason }),
  listStoreQrcodes: (q) => client.get("/ops/stores/qrcodes", q),
  recordQrcodePrint: ({ merchantNo, ...body }) =>
    client.post(`/ops/stores/${merchantNo}/qrcode/print`, body),
  listStoreAcquisition: (q) => client.get("/ops/stores/acquisition", q),
  listStoreTemplates: () => client.get("/ops/stores/templates"),
  saveStoreTemplate: (v) => client.post("/ops/stores/templates", v),
  setStoreTemplateEnabled: (templateNo, enabled) => client.post(`/ops/stores/templates/${templateNo}/enabled`, { enabled }),
};
