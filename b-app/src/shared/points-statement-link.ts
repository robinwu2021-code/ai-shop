export interface PointsLinkContext {
  storeNo: string;
  ensureScope(): Promise<unknown>;
  ensureEntityGroups(): Promise<{ stores: { storeNo: string }[] }[]>;
  switchStore(storeNo: string): Promise<unknown>;
  can(permission: string): boolean;
}
export async function preparePointStatementLink(merchant: PointsLinkContext,
  query: { storeNo?: string; typeCode?: string; statementNo?: string }) {
  await merchant.ensureScope();
  if (!query.storeNo || !query.typeCode || !query.statementNo
    || [query.storeNo, query.typeCode, query.statementNo].some(x => x.length > 120)) {
    throw new Error("POINTS_LINK_INVALID");
  }
  const groups = await merchant.ensureEntityGroups();
  if (!groups.some(group => group.stores.some(store => store.storeNo === query.storeNo))) {
    throw new Error("POINTS_LINK_STORE_DENIED");
  }
  if (merchant.storeNo !== query.storeNo) await merchant.switchStore(query.storeNo);
  await merchant.ensureScope();
  if (merchant.storeNo !== query.storeNo || !merchant.can("biz:finance")) {
    throw new Error("POINTS_LINK_FINANCE_DENIED");
  }
  return { typeCode: query.typeCode, statementNo: query.statementNo };
}
