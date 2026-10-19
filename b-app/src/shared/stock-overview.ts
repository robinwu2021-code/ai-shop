import type { StockOverview } from "@shared/types";

/** One bounded HTTP lookup for the current list, ignoring unmapped physical items. */
export async function loadStockOverviewMap(
  storeNo: string,
  rows: { skuNo?: string | null }[],
  fetchBatch: (storeNo: string, skuNos: string[]) => Promise<StockOverview[]>,
): Promise<Record<string, StockOverview>> {
  const keys = [...new Set(rows.flatMap(r => r.skuNo ? [r.skuNo] : []))];
  if (!keys.length) return {};
  const result = await fetchBatch(storeNo, keys);
  const requested = new Set(keys);
  return Object.fromEntries(result.filter(r => r.storeNo === storeNo && requested.has(r.skuNo))
    .map(r => [r.skuNo, r]));
}

/** Response validity includes both request generation and the live store context. */
export function createStockRequestScope() {
  let generation = 0;
  return {
    begin(storeNo: string) { return { generation: ++generation, storeNo }; },
    current(request: { generation: number; storeNo: string }, liveStoreNo: string) {
      return request.generation === generation && request.storeNo === liveStoreNo;
    },
  };
}
