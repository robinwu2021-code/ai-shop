// 商品编辑页的**销售规格**：维度、档位、以及它们笛卡尔积出来的 SKU 矩阵。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么单独一个文件
// ─────────────────────────────────────────────────────────────────────────────
// 这一块自成一体：**规格组一变，矩阵就得重建**，而重建要按选项组合把已填的
// 价与库存继承过来（`rebuild` 里那三级回落）。整块只有三样东西跨出去 ——
// SKU 行本身（`rows`，价格块的）、按哪个品类取模板（`type`）、按哪个类目取
// 候选维度（`categoryNo`），所以它们是入参。
//
// <p>**搬过来的实现一个字没改。** 同一次改动里删掉的那条「自动套规格」死链
// （`autoApplyDefaultSpec` / `primeMainGroup` 等）是上一个提交的事，与这里无关。
//
// <p>`moreOther` 这条 2026-08-27 曾经落在「十一、参数」那一节里 —— 它算的是
// **还能加哪些规格维度**，与参数无关。分节标题说了假话就不如没有；
// 现在它连同整块一起搬进了名字就叫规格的文件。
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import type { Ref } from "vue";
import { api } from "@/api";
import { ROUTES } from "@/shared/nav";
import { emptyPrices } from "./price-rows";
import type { Row } from "./price-rows";
import type { CategoryType, SpecOption, SpecTemplate } from "@shared/types";

/**
 * 规格维度 · 档位 · SKU 矩阵。
 *
 * @param rows       SKU 行（价格块持有）—— 重建矩阵改的就是它
 * @param type       品类（标品/生鲜/服务）：取平台模板要带上
 * @param categoryNo 当前类目：模板与候选维度都按它取，换类目要重新拉
 */
export function useSpecGroups(
  rows: Ref<Row[]>,
  type: Ref<CategoryType>,
  categoryNo: Ref<string>,
) {
  const { t } = useI18n();

  /** 规格组。空 = 单规格商品 */
  const groups = ref<{ name: string; options: string[]; codes?: (string | undefined)[]; templateNo?: string }[]>([]);
  /** 可用模板：平台按类目预置 + 本商家存的常用 */
  const templates = ref<SpecTemplate[]>([]);
  /** 「加规格组」时的维度选择面板：本类目已配 → 平台通用 → 自建 */
  const pickableDims = ref<SpecTemplate[]>([]);

  const multi = computed(() => groups.value.length > 0);

  /**
   * 加一个规格维度要多填几行 —— **当场说出来**。
   *
   * <p>「3 × 2 = 6 个规格，要填 6 个价和库存」。此前页面从不提这件事，
   * 商家加完第二个维度才发现要填一屏，而那时他已经填了一半。
   * 只在**两个维度起**才显示：一个维度时「3 个档位 = 3 行」是自明的。
   */
  const skuCost = computed(() => {
    const counts = groups.value
      .map((g) => g.options.filter((o) => o.trim()).length)
      .filter((n) => n > 0);
    if (counts.length < 2) return "";
    const n = counts.reduce((a, b) => a * b, 1);
    return t("goods.skuCost", { s: counts.join(" × "), n });
  });

  // ── 九、SKU 矩阵：重建与模板 ────────────────────────────────────────────
  //    规格组的笛卡尔积 → 行；套模板是它的入口
  function keyOf(values: string[]): string {
    return values.join("");
  }

  /**
   * 规格一变就重建矩阵。**不再只靠 `@blur`**。
   *
   * <p>此前重建的唯一触发点是输入框失焦。实测踩过：用系统返回键收键盘
   * **不触发 blur**，矩阵就停在旧状态 —— 屏幕上写着两个选项，底下却只有一行 SKU，
   * 而这时点保存，发出去的是「声明了 2 个选项、只带 1 个 SKU」的不一致包体。
   * 真实用户点保存时 blur 通常会先触发，但那是时序上的巧合，不是设计。
   *
   * <p>防抖 250ms：每敲一个字符重建一次，矩阵会在打字过程中反复闪；
   * 停手四分之一秒再重建，观感上就是「填完就出来了」。
   */
  let rebuildTimer: ReturnType<typeof setTimeout> | undefined;
  watch(
    groups,
    () => {
      clearTimeout(rebuildTimer);
      rebuildTimer = setTimeout(rebuild, 250);
    },
    { deep: true },
  );

  /** 规格组变化后重建矩阵，按选项组合保留已填的价与库存 */
  function rebuild() {
    if (!groups.value.length) {
      rows.value = [
        {
          skuNo: rows.value[0]?.skuNo,
          optionValues: [],
          priceMajor: rows.value[0]?.priceMajor ?? emptyPrices(),
          stock: rows.value[0]?.stock ?? "0",
          originMajor: rows.value[0]?.originMajor ?? "",
          nominalGram: rows.value[0]?.nominalGram ?? "",
          costMajor: rows.value[0]?.costMajor ?? "",
          // 外部身份跟着第一行走：单规格拆成多规格时，原来那条 SKU 的条码不该凭空消失
          barcode: rows.value[0]?.barcode ?? "",
          merchantSkuCode: rows.value[0]?.merchantSkuCode ?? "",
          saleUnit: rows.value[0]?.saleUnit ?? "",
        },
      ];
      return;
    }
    const before = rows.value;
    const prev = new Map(before.map((r) => [keyOf(r.optionValues), r]));
    let combos: string[][] = [[]];
    for (const g of groups.value) {
      const opts = g.options.map((o) => o.trim()).filter(Boolean);
      if (!opts.length) continue;
      combos = combos.flatMap((c) => opts.map((o) => [...c, o]));
    }

    /**
     * 新组合**继承哪一行的价与库存**。
     *
     * <p>只按精确键匹配是不够的 —— `keyOf` 是把选项值拼起来，所以**增删规格组会让
     * 每一个键都变**：单规格的键是 `""`，加一组「尺寸」之后变成 `"S"`/`"M"`，
     * 一个都对不上，于是店主刚填的价与库存**全部清零**。
     * 而页面底部一直写着「改规格时已填的价与库存会按选项组合保留」——
     * 文案与行为对不上，且清零不报错，他要滚回去才发现。
     *
     * <p>三级回落，从最精确到最合理：
     *   1. 精确命中（同组内增删选项，原行原样保留）
     *   2. **前缀命中**（在已有规格上再加一组：`["S"]` → `["S","红"]` 继承 `["S"]`）
     *   3. **单行回落**（单规格 → 多规格：只有一行可继承，那就是它）——
     *      店主说「这个商品现在有 S 和 M」时，他的意思显然是两个都从刚填的价起步
     */
    const inherit = (values: string[]): Row | undefined => {
      const exact = prev.get(keyOf(values));
      if (exact) return exact;
      const prefix = before.find(
        (r) => r.optionValues.length > 0
          && r.optionValues.length < values.length
          && r.optionValues.every((v, i) => v === values[i]),
      );
      if (prefix) return prefix;
      return before.length === 1 ? before[0] : undefined;
    };

    rows.value = combos.map((values) => {
      const old = inherit(values);
      return {
        // **skuNo 只跟精确命中走** —— 前缀/单行回落继承的是「价与库存」这类可重填的值，
        // 而 skuNo 是身份：两行拿同一个编号，历史订单与库存流水就指向了错的规格
        skuNo: prev.get(keyOf(values))?.skuNo,
        optionValues: values,
        /*
         * **必须拷一份，不能直接传引用。**
         *
         * `inherit()` 会让多个新组合回落到**同一个** old 行（前缀匹配、
         * 或单行回落那条）。直接传 `old.priceMajor` 的话，四个规格共用一个对象 ——
         * 改「袋装」的价，「盒装/桶装/整箱」跟着一起变，而且保存下去就是那样。
         *
         * 这个 bug 从初始提交就在，一直没被发现，因为 `stock` 是字符串（值类型）
         * 不受影响：表面症状只是「价格不对劲」，像操作失误，
         * 而不像四行绑到了同一份数据。多规格商品因此从来没能分别定价过。
         */
        priceMajor: old ? { ...old.priceMajor } : emptyPrices(),
        stock: old?.stock ?? "0",
        originMajor: old?.originMajor ?? "",
        nominalGram: old?.nominalGram ?? "",
        // 成本跟着价一起继承：加一个包装规格，进价多半还是那个进价
        costMajor: old?.costMajor ?? "",
        barcode: old?.barcode ?? "",
        merchantSkuCode: old?.merchantSkuCode ?? "",
        // 单位多半整件货一样：没有旧值时沿用第一行的，省得逐行敲「斤」
        saleUnit: old?.saleUnit ?? rows.value[0]?.saleUnit ?? "",
      };
    });
  }



  /**
   * 拉规格模板。**要带上已选类目** —— 只传品类拿到的是兜底那批，
   * 而品类只有 3 个、二级类目有 32 个，STANDARD 一个就盖住 18 个：
   * 手机数码与鲜花会共用「包装：袋装/瓶装/罐装」，等于没有推荐。
   */
  async function loadTemplates() {
    templates.value = await api
      .mSpecTemplates(type.value, categoryNo.value || undefined)
      .catch(() => []);
    // 这里只取回模板，**不建组** —— 建组是商家点 chip 的事（见 pickDim）
  }

  /**
   * 取「还能加哪些维度」。
   *
   * <p><b>选完类目就取，不等他点开面板</b>：规格区末尾那行「这一类还能按 …」
   * 靠它渲染，懒加载的话那一行永远不出现 —— 而它恰恰是让商家知道
   * 「这一类不止一种分法」的唯一地方。
   *
   * <p>换了类目要重取：候选是按类目算的，留着上一类的会推错。
   */
  async function loadPickableDims() {
    pickableDims.value = await api.mPickableDims(categoryNo.value || undefined).catch(() => []);
  }

  /** 挑中一个平台/自建维度：连同它的取值一起进来，值编号跟着走 */
  function pickDim(tpl: SpecTemplate) {
    // 候选列表已经滤过一遍，这里再兜一道：同名即同一件事，编号不同是内部实现
    if (usedDimNames.value.has(tpl.name.trim())
        || groups.value.some((g) => g.templateNo === tpl.templateNo)) {
      uni.showToast({ title: t("goods.dimAlready"), icon: "none" });
      return;
    }
    /*
     * **档位默认全开**，与「我的规格」里加一个规格时同一条规矩：
     * 他加这个维度就是要用它，进来却是一排关着的档位，还得再点一遍才算数。
     * 不合适的那几档点掉就是了 —— 这一页只做减法。
     *
     * <p>没有档位可带的（自建维度刚建出来还没配值）才留一个空位。
     */
    // 同上：加进来的规格档位也一个不预选，灰着等他点
    groups.value.push({ name: tpl.name, options: [], codes: [], templateNo: tpl.templateNo });
    rebuild();
  }

  /**
   * 都不是，自己起一个名。**留着这条路，但把它放在最后** ——
   * 商家确实会有平台没想到的维度（「辣度」「打磨程度」），
   * 堵死它的结果是他退回「＋ 规格组」手输，那才是真正掉出聚合的那条路。
   */
  /**
   * 去「商品规格」加新的规格或档位。
   *
   * <p>**新增只在那一处。** 那里加一次全店通用、有编号、参与跨店比价；
   * 在建品页手输只对这一件商品有效，而且从此掉出聚合 —— 代价看不见，
   * 所以不能把这条路留在这里让人顺手走。
   */
  function gotoMySpecs() {
    uni.navigateTo({ url: ROUTES.mySpecs });
  }

  function removeGroup(i: number) {
    groups.value.splice(i, 1);
    rebuild();
  }

  /**
   * 面板里的三段。**顺序就是建议顺序**：这一类目平台配好的最该选，
   * 通用维度次之，自己建过的放最后（它们不参与跨店聚合）。
   */
  /** 「通用规格」那一组是否展开。默认收起 —— 见模板里那段注释 */
  const showUniversalDims = ref(false);

  /**
   * 已经在用的维度**不再出现在候选里**。
   *
   * <p>此前只在 `pickDim` 里按 `templateNo` 拦一道，而同一个「重量」在类目绑定
   * 与候选池里是**两个不同的 templateNo**（一个来自 /biz/spec-templates，
   * 一个来自 /biz/spec-dims）—— 于是拦不住，点一下就多出第二个「重量」组，
   * 两组档位还不一样（一组是本店确认过的，一组是平台原样）。
   *
   * <p><b>按名字判重</b>：两个同名维度在商家眼里就是同一件事，编号不同是我们的内部事情。
   */
  const usedDimNames = computed(
    () => new Set(groups.value.map((g) => g.name.trim()).filter(Boolean)),
  );

  function unused(list: SpecTemplate[]): SpecTemplate[] {
    return list.filter(
      (d) => !usedDimNames.value.has(d.name.trim())
        && !groups.value.some((g) => g.templateNo === d.templateNo),
    );
  }

  /**
   * 本类目还没用上的维度 —— **摆在眼前，不藏在面板后面**。
   *
   * <p>平台已经替这一类回答过「该按什么分」（蔬菜：重量 / 包装 / 等级），
   * 而默认只带出主维度那一个。其余几条藏在「＋ 规格」两层之后的话，
   * 商家会以为这一类只能按一种方式分 —— 那是平台配置白做了。
   *
   * <p><b>只摆出来让他点，不自动加。</b>三条全自动带出来意味着每件菜都变成
   * 一堆 SKU（3 × 4 × 4），那是帮倒忙。
   */
  const moreFromCategory = computed(() =>
    unused(pickableDims.value.filter((d) => d.categoryNo)),
  );

  /**
   * 通用与自建的维度 —— **收在「更多」后面**。
   *
   * <p>`universal` 的判据是「值的含义跨类目一致」（给跨店聚合用），
   * 不是「哪些类目该用它」，所以手机数码下面会并排摆着口味、等级、尺码。
   * 不拦着他选，但也不把二十来个无关维度摆在眼前。
   */

  const moreOther = computed(() => {
    /*
     * **还要跟前面那批去重。** 通用池里的「包装」与类目绑定的「包装」是
     * 两个不同的 templateNo，`unused()` 只挡「已经在用的」，挡不住这一对 ——
     * 展开「更多」后会看到两个「＋ 包装」，点哪个都对，但看起来像出了错。
     * 与判重同一条规矩：同名即同一件事。
     */
    const shown = new Set(moreFromCategory.value.map((d) => d.name.trim()));
    const seen = new Set<string>();
    return unused([
      ...pickableDims.value.filter((d) => !d.categoryNo && d.scope === "PLATFORM"),
      ...pickableDims.value.filter((d) => d.scope === "MERCHANT"),
    ]).filter((d) => {
      const n = d.name.trim();
      if (shown.has(n) || seen.has(n)) return false;
      seen.add(n);
      return true;
    });
  });

  // ── 十二、选项勾选 ──────────────────────────────────────────────────────────
  //    维度内选值，并上「已经在用的」以兼容老数据
  /**
   * 这一组能出现的**全部**档位：本店规格库里配的 ∪ 这件商品已经在用的。
   *
   * <p>并上「已经在用的」是为了老数据：早年手输的值不在规格库里，
   * 不并的话它们会从界面上消失，而商品身上还带着 —— 他会以为规格丢了。
   */
  function allOptionsOf(gi: number): SpecOption[] {
    const g = groups.value[gi];
    if (!g) return [];
    const tpl = templates.value.find((t) => t.templateNo === g.templateNo)
      ?? pickableDims.value.find((t) => t.templateNo === g.templateNo);
    const out: SpecOption[] = [...(tpl?.options ?? [])];
    const known = new Set(out.map((o) => o.label));
    g.options.forEach((label, i) => {
      const l = label.trim();
      if (l && !known.has(l)) {
        out.push({ label: l, code: g.codes?.[i] } as SpecOption);
        known.add(l);
      }
    });
    return out;
  }

  /** 这一档这件商品有没有 */
  function optionOn(gi: number, o: SpecOption): boolean {
    return !!groups.value[gi]?.options.some((x) => x.trim() === o.label);
  }

  /**
   * 点一下开合这一档。
   *
   * <p><b>建品页只做减法。</b>新的规格与档位统一在「商品规格」里加 ——
   * 那里加一次全店通用，而在建品页手输的值没有值编号，
   * 三家店的「500g」「五百克」「0.5kg」永远聚不到一起，比价也就不成立。
   * 这里能点回来的只是**本店已有的那些**，不是新造。
   *
   * <p>顺序始终按规格库的顺序重排，不按他点击的先后 ——
   * 否则同一个维度在不同商品上顺序不同，价格表看起来像是乱的。
   */
  function toggleOption(gi: number, o: SpecOption) {
    const g = groups.value[gi];
    if (!g) return;
    const on = optionOn(gi, o);
    const next = new Set(g.options.map((x) => x.trim()).filter(Boolean));
    if (on) {
      if (next.size <= 1) return;   // 最后一档不给关：一个档位都没有的规格组没有意义
      next.delete(o.label);
    } else {
      next.add(o.label);
    }
    const all = allOptionsOf(gi);
    g.options = all.filter((x) => next.has(x.label)).map((x) => x.label);
    g.codes = all.filter((x) => next.has(x.label)).map((x) => x.code || undefined);
    rebuild();
  }
  return {
    groups, templates, pickableDims, multi, skuCost,
    rebuild, loadTemplates, loadPickableDims,
    pickDim, gotoMySpecs, removeGroup,
    showUniversalDims, moreFromCategory, moreOther,
    allOptionsOf, optionOn, toggleOption,
  };
}
