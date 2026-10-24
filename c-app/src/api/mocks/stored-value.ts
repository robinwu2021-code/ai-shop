import { storedValueDateTime } from "@/shared/stored-value-time";
import type { ShopApi, StoredValueRecord } from "../contract";
const store = { storeNo: "STORE_SV_DEMO", storeName: "悦颜美肤 · 科技园店", businessTimeZone: "Asia/Shanghai" };
const records: StoredValueRecord[] = [
  { type: "REDEMPTION", title: "面部护理", currency: "CNY", signedAmountMinor: -12800, occurredAt: "2026-10-21T06:30:00Z", documentNo: "EU20261021000001" },
  { type: "RECHARGE", title: "日常储值", currency: "CNY", signedAmountMinor: 13000, occurredAt: "2026-10-19T16:20:00Z", documentNo: "PR20261020000001" },
];
function notFound(): never { throw Object.assign(new Error("Not found"), { code: 10404 }); }
export const storedValueMock: Pick<ShopApi, "storedValuePendingServices"|"storedValueService"|"storedValueAuthorize"|"storedValueIssuers"|"storedValueLocateIssuer"|"storedValueIssuerRecords"|"storedValueStores" | "storedValueRecords" | "storedValueNotificationCapability"> = {
  async storedValuePendingServices(){return [];},
  async storedValueService(){return notFound();},
  async storedValueAuthorize(){return notFound();},
  async storedValueIssuers(query){
    return {records:query?.cursor?[]:[{issuerRef:"DEMO_OWNED_ISSUER",scopeType:"STORE" as const,name:store.storeName,storeNo:store.storeNo,entityNo:"DEMO_ENTITY",currency:"CNY" as const,businessTimeZone:store.businessTimeZone,lastType:"REDEMPTION" as const,lastOccurredAt:records[0]!.occurredAt}],nextCursor:null,hasMore:false};
  },
  async storedValueLocateIssuer(documentNo){
    if(!records.some(r=>r.documentNo===documentNo))notFound();
    return (await storedValueMock.storedValueIssuers()).records[0]!;
  },
  async storedValueIssuerRecords(issuerRef,query){
    if(issuerRef!=="DEMO_OWNED_ISSUER")notFound();
    const issuer=(await storedValueMock.storedValueIssuers()).records[0]!;
    const page=await storedValueMock.storedValueRecords(store.storeNo,query);
    return {...page,issuer,records:page.records.map(r=>({...r,storeNo:store.storeNo,storeName:store.storeName}))};
  },
  async storedValueStores(query) {
    return { records: query?.cursor ? [] : [{ storeNo: store.storeNo, storeName: store.storeName, lastOccurredAt: records[0]!.occurredAt, lastType: "REDEMPTION" }], nextCursor: null, hasMore: false };
  },
  async storedValueRecords(storeNo, query = {}) {
    if (storeNo !== store.storeNo) notFound();
    if (query.focusDocumentNo && query.cursor) throw Object.assign(new Error("Invalid cursor"), { code: 10400 });
    let filtered = records.filter(record => (!query.type || query.type === "ALL" || record.type === query.type)
      && (!query.startDate || storedValueDateTime(record.occurredAt, store.businessTimeZone).slice(0, 10) >= query.startDate)
      && (!query.endDate || storedValueDateTime(record.occurredAt, store.businessTimeZone).slice(0, 10) <= query.endDate));
    if (query.focusDocumentNo) {
      const index = filtered.findIndex(record => record.documentNo === query.focusDocumentNo);
      if (index < 0) notFound();
      filtered = filtered.slice(index);
    }
    const offset = Number(query.cursor || 0); const size = query.size || 20;
    const next = offset + size; const hasMore = next < filtered.length;
    return { store, filter: { type: query.type || "ALL", startDate: query.startDate || null, endDate: query.endDate || null },
      records: filtered.slice(offset, next), nextCursor: hasMore ? String(next) : null, hasMore };
  },
  async storedValueNotificationCapability() {
    // Mock绝不伪造可用微信模板或原生授权。
    return { channel: "WXSUB", available: false, templateId: null, reasonCode: "NOT_CONFIGURED" };
  },
};
