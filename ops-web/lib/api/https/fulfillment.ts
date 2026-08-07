// 覆盖范围：履约调度（P-5.1）。
import { client } from "../http-client";
import type { FulfillmentApi } from "../contracts/fulfillment";

export const fulfillmentHttp: FulfillmentApi = {
  listArrivalBatches: (q) => client.get("/ops/fulfillment/batches", q),
  setBatchStatus: (batchNo, status) => client.post(`/ops/fulfillment/batches/${batchNo}/status`, { status }),
  listSorting: (q) => client.get("/ops/fulfillment/sorting", q),
  listRedeemStats: (q) => client.get("/ops/fulfillment/redeem", q),
  getOverdueRule: () => client.get("/ops/fulfillment/overdue-rule"),
  saveOverdueRule: (rule) => client.post("/ops/fulfillment/overdue-rule", rule),
  listShipments: (q) => client.get("/ops/shipments", q),
  updateWaybill: (v) => client.post(`/ops/shipments/${v.shipmentNo}/waybill`, v),
  listFreightTemplates: (q) => client.get("/ops/freight-templates", q),
  saveFreightTemplate: (v) => client.post("/ops/freight-templates", v),
  archiveFreightTemplate: (templateNo) => client.post(`/ops/freight-templates/${templateNo}/archive`),
  unarchiveFreightTemplate: (templateNo) => client.post(`/ops/freight-templates/${templateNo}/unarchive`),
  listCarriers: () => client.get("/ops/fulfillment/carriers"),
  saveCarrier: (v) => client.put(`/ops/fulfillment/carriers/${v.carrier}`, v),
  setCarrierEnabled: (carrier, enabled) => client.post(`/ops/fulfillment/carriers/${carrier}/enabled`, { enabled }),
};
