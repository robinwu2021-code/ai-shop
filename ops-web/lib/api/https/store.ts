// 覆盖范围：门店主页治理（P-10.1）。
import { client } from "../http-client";
import type { StoreApi } from "../contracts/store";

export const storeHttp: StoreApi = {
  listStoreAudits: (q) => client.get("/ops/stores/audits", q),
  decideStoreAudit: (auditNo, pass, reason) => client.post(`/ops/stores/audits/${auditNo}/decide`, { pass, reason }),
  listStoreQrcodes: (q) => client.get("/ops/stores/qrcodes", q),
  listStoreAcquisition: (q) => client.get("/ops/stores/acquisition", q),
  listStoreTemplates: () => client.get("/ops/stores/templates"),
  saveStoreTemplate: (v) => client.post("/ops/stores/templates", v),
  setStoreTemplateEnabled: (templateNo, enabled) => client.post(`/ops/stores/templates/${templateNo}/enabled`, { enabled }),
};
