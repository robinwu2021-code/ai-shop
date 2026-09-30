// 历史记录：搜过的词、看过的料号。只存在这台手机上 —— 它们是顺手的快捷方式，不是数据；
// 询价、求购那些有据可查的记录走接口，不在这里。
const SEARCH_KEY = "she_elec_recent";
const VIEWED_KEY = "she_elec_viewed";
const SEARCH_MAX = 20;
const VIEWED_MAX = 50;

function read<T>(key: string, ok: (x: unknown) => x is T): T[] {
  try {
    const v = uni.getStorageSync(key) as unknown;
    return Array.isArray(v) ? v.filter(ok) : [];
  } catch {
    return [];
  }
}

function write(key: string, list: unknown[]): void {
  try {
    uni.setStorageSync(key, list);
  } catch {
    // 存不上就算了
  }
}

function drop(key: string): void {
  try {
    uni.removeStorageSync(key);
  } catch {
    // 同上
  }
}

const isStr = (x: unknown): x is string => typeof x === "string";

export function recentSearches(): string[] {
  return read(SEARCH_KEY, isStr);
}

export function rememberSearch(keyword: string): void {
  const k = keyword.trim();
  if (!k) return;
  write(SEARCH_KEY, [k, ...recentSearches().filter((x) => x.toUpperCase() !== k.toUpperCase())].slice(0, SEARCH_MAX));
}

export function clearSearches(): void {
  drop(SEARCH_KEY);
}

/** 看过的一个料号。只记打开详情时手上就有的几样，列表里不再拉接口 */
export interface ViewedPart {
  partNo: string;
  mpn: string;
  /** 厂牌名；没认出来时为空 */
  mfr: string;
  /** 看的时间（毫秒） */
  at: number;
}

const isViewed = (x: unknown): x is ViewedPart =>
  !!x && typeof x === "object" && isStr((x as ViewedPart).partNo) && isStr((x as ViewedPart).mpn);

export function viewedParts(): ViewedPart[] {
  return read(VIEWED_KEY, isViewed);
}

export function rememberViewed(p: Omit<ViewedPart, "at">, now = Date.now()): void {
  if (!p.partNo) return;
  write(VIEWED_KEY, [{ ...p, at: now }, ...viewedParts().filter((x) => x.partNo !== p.partNo)].slice(0, VIEWED_MAX));
}

export function clearViewed(): void {
  drop(VIEWED_KEY);
}
