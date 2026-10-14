// 地名的**纯字符串**规则：官方机构名 → 商家嘴里的地名、两个写法算不算同一个地方、
// 从一句关键词里抠出市名。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么单独一个文件
// ─────────────────────────────────────────────────────────────────────────────
// 这几条规矩此前散在 `biz-region-picker.vue` 的 1117 行里，而它们的共同点是
// **一个字符串进、一个字符串出**：不碰接口、不碰组件状态，却决定了商家看到
// 「景滑村委会」和「景滑」是一条还是两条。这种规则值得能被直接测到 ——
// 它出过一次真故障（真机上搜「景滑村」出两条），而当时端上与后端各写了一套。
//
// <p>`normalizeName` 与后端 `PlaceNames.norm` 是**同一份词表**，
// `packages/shared/tests/region-names.test.ts` 盯着两边不许分叉。
// 分叉不会报错：只是同一个地方在搜索里出现两次，谁也看不出为什么。

/**
 * 「富城村村民委员会」→「富城村」：聚落叫的是**地名**，不是机构名。
 *
 * <p>官方名是机构名（「牛杜村委会」「茜坑社区居委会」），而商家嘴里是地名
 * （「牛杜村」「茜坑社区」）。去掉的只是「委员会」那一截，**地名的通名要留着** ——
 * 此前一并吃掉，牛杜村委会变成了「牛杜」，而搜索里显示「牛杜」、名录里显示
 * 「牛杜村委会」、已开通里又是「牛杜村」，同一个地方三种写法，看着像三层。
 */
export function cleanVillageName(official: string): string {
  const cleaned = official
    .replace(/(村民委员会|村委会)$/, "村")
    .replace(/(居民委员会|居委会)$/, "社区")
    .replace(/委员会$/, "")
    // 「富城村村民委员会」→「富城村村」：通名重了收掉一个
    .replace(/村村$/, "村")
    .replace(/社区社区$/, "社区");
  return cleaned || official;
}

/**
 * 归一化：「阳光花园」「阳光花园小区」「阳光花园(北区)」在商家嘴里是同一个地方。
 *
 * <p>**「村委会」必须单独列在词表里**，不能指望「村」+「委会」拼出来 ——
 * 「委会」不在词表里，漏了这一条会让「景滑村委会」（官方机构名）穿过归一化，
 * 跟「景滑」（商家起的名）判成两个不同的地方。真机上搜「景滑村」出过两条，
 * 根子就在这里；后端 `PlaceNames.norm` 同一处也补了。
 *
 * <p>⚠️ 改这里的词表就要同时改 `PlaceNames.java`，闸门会核对。
 */
export function normalizeName(s: string): string {
  return s.replace(/[（(].*?[）)]/g, "")
    .replace(/(小区|花园|家园|新村|苑|园|村委会|村|社区|居委会|村民委员会|居民委员会)+$/g, "")
    .trim();
}

/**
 * 两个写法**是不是同一个地方**：归一化之后相等，或者一个是另一个的前缀。
 *
 * <p>前缀也算，是因为商家建档时常常只写一半（「阳光花园」vs「阳光花园东区」）。
 * 归一化后为空的（整串都是通名，如「村委会」）一律不算 ——
 * 空串是任何串的前缀，认了它会把所有地方判成同一个。
 */
export function sameishName(a: string, b: string): boolean {
  const x = normalizeName(a);
  const y = normalizeName(b);
  if (!x || !y) return false;
  return x === y || x.startsWith(y) || y.startsWith(x);
}

/** 这条像不像一个「里面还有小区」的聚落（村/社区/居委会） */
export function looksLikeContainer(name: string, kind?: string): boolean {
  return kind === "VILLAGE" || /(社区|居委会|村委会|村)$/.test(name);
}

/** 市名的通名。抠市名只认这几个结尾 */
export const CITY_SUFFIXES = ["市", "自治州", "地区", "盟"];

/**
 * 「深圳市龙华区福安雅园」→「深圳市」。只认省市这两级前缀。
 *
 * <p>市名是给高德缩范围用的：在根级直接搜时没有面包屑，不从关键词里抠出来的话
 * `poiSearchInCity` 拿到的 city 是空串，退化成全国搜「福安雅园」——
 * 同名的、更有名的候选会把真正要的那条挤下去。
 */
export function guessCityFrom(kw: string): string | undefined {
  for (const suf of CITY_SUFFIXES) {
    const i = kw.indexOf(suf);
    if (i > 0 && i <= 6) return kw.slice(0, i + suf.length);
  }
  return undefined;
}

/** 「浙江省 / 杭州市 / 西湖区」→「西湖区」。提示语里只需要末级，整条路径会把话挤没 */
export function shortName(name?: string): string {
  return (name ?? "").split(" / ").pop() ?? "";
}
