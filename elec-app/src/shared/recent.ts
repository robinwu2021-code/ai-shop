// 最近搜过。只存在这台手机上：它是一个顺手的快捷方式，不是数据
const KEY = "she_elec_recent";
const MAX = 8;

export function recentSearches(): string[] {
  try {
    const v = uni.getStorageSync(KEY) as unknown;
    return Array.isArray(v) ? (v as string[]).filter((x) => typeof x === "string") : [];
  } catch {
    return [];
  }
}

export function rememberSearch(keyword: string): void {
  const k = keyword.trim();
  if (!k) return;
  const list = [k, ...recentSearches().filter((x) => x.toUpperCase() !== k.toUpperCase())].slice(0, MAX);
  try {
    uni.setStorageSync(KEY, list);
  } catch {
    // 存不上就算了
  }
}

export function clearSearches(): void {
  try {
    uni.removeStorageSync(KEY);
  } catch {
    // 同上
  }
}
