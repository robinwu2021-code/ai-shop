import type { SpecOverride, SpecTemplate, StoreCategorySpecs } from "@shared/types";

/**
 * 把「本店在这个类目下的规格与参数」拼成一份提交载荷。
 *
 * <p><b>后端是先清后写，所以每次都要带上这个类目下的全部当前状态</b> ——
 * 销售规格与商品参数**两个列表一起**。只发一半的话另一半的覆盖会被清掉：
 * 改过的本店叫法丢了、移除过的规格自己回来了，而这两件都不报错。
 * 移除是靠一条 `enabled:false` 表达的，所以被移除的那些也必须留在载荷里。
 *
 * <p>抽出来是因为它有两个调用方（「商品规格和参数」页与建品页的加参数），
 * 而这份规则一旦两处各写一遍，迟早会有一处漏掉「两个列表一起」——
 * 那次故障的症状是「我上次改的本店叫法怎么没了」，指向的方向完全不对。
 */
export function buildSpecOverride(opts: {
  g: StoreCategorySpecs;
  /**
   * 想要的顺序（templateNo）。只需给关心的那一段，
   * 两个列表里没被提到的会**按原顺序跟在后面** —— 不重排别人。
   */
  order?: string[];
  /** 刚加进来、还不在 g 里的那一个 */
  added?: SpecTemplate;
  /** 对某一个规格的改动：档位取舍与本店叫法 */
  patch?: { dimNo: string; label?: string; values: string[]; dropped: string[] };
  /** 移除：以一条 enabled=false 显式表达，不是「不提交」 */
  removeDimNo?: string;
}): SpecOverride[] {
  const { g, order, added, patch, removeDimNo } = opts;
  const all = [...(g.dims ?? []), ...(g.props ?? []), ...(added ? [added] : [])];

  const seq = [...(order ?? [])];
  for (const t of all) {
    if (!seq.includes(t.templateNo)) seq.push(t.templateNo);
  }

  const dims: SpecOverride[] = seq
    .filter((no) => no !== removeDimNo)
    .map((no) => {
      const t = all.find((x) => x.templateNo === no);
      const isPatched = patch && patch.dimNo === no;
      const codes = isPatched ? patch.values : (t?.options ?? []).map((o) => o.code ?? "");
      const gone = isPatched ? patch.dropped : [];
      const label = isPatched ? patch.label : t?.name;
      return {
        dimNo: no,
        enabled: true,
        /*
         * **原样提交，不在这里判「改没改」。**端上手里的 name 已经是合并后的，
         * 要比对得另外拿一份平台原名，而那个值只在部分路径上才有 ——
         * 判漏了就落一堆等于原名的覆盖，而那会让运营以后的改名到不了这家店。
         * 后端有平台原名，让它去比。
         */
        label: label?.trim() || undefined,
        values: [
          ...codes.map((code) => ({ code, enabled: true })),
          ...gone.map((code) => ({ code, enabled: false })),
        ],
      };
    });

  if (removeDimNo) {
    dims.push({ dimNo: removeDimNo, enabled: false, label: undefined, values: [] });
  }
  return dims;
}
