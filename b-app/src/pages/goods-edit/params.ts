// 商品编辑页的**商品参数**：产地 / 保质期 / 材质这一类。
//
// ─────────────────────────────────────────────────────────────────────────────
// 与销售规格分开的理由是**性质**，不是范围
// ─────────────────────────────────────────────────────────────────────────────
// 规格进笛卡尔积生成 SKU，每一档要单独定价与备库存；参数一项也不进 ——
// 买家不用挑，只是看。混在一起的话「本地 × 500g」会变成一个要单独定价备货的行，
// 而商家只想说「这袋菜是本地的」。
//
// <p>它跨出去的只有一样：**按哪个类目取候选**。所以 `categoryNo` 是入参。
// 搬过来的实现一个字没改。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { buildSpecOverride } from "@/utils/spec-override";
import type { Ref } from "vue";
import type { GoodsParam, SpecOption, SpecTemplate } from "@shared/types";

/**
 * 商品参数的全部状态与动作。
 *
 * @param categoryNo 当前类目 —— 候选参数按它取（换类目要重新 `loadProps()`）
 */
export function useGoodsParams(categoryNo: Ref<string>) {
  const { t } = useI18n();

  // ── 十一、参数 ──────────────────────────────────────────────────────────────
  //    不参与组合的属性（产地、材质）—— 与规格分开的理由见 propDims
  /*
   * 商品参数（V250）：产地 / 保质期 / 材质这一类。
   *
   * <p><b>与销售规格分开的理由是性质，不是范围</b>：规格进笛卡尔积生成 SKU，
   * 每一档要单独定价与备库存；参数一项也不进，买家不用挑，只是看。
   * 混在一起的话「本地 × 500g」变成一个要单独定价备货的行，
   * 而他只想说「这袋菜是本地的」。
   */
  const propDims = ref<SpecTemplate[]>([]);
  /** dimNo → 已选的那一项。量纲型没有候选值，存的是他自己填的文字 */
  const paramValues = ref<Record<string, GoodsParam>>({});

  async function loadProps() {
    propDims.value = await api.mSpecProps(categoryNo.value || undefined).catch(() => []);
  }

  /*
   * **参数可以在这里现加**（规格不行）。
   *
   * <p>两者的代价不一样。规格进笛卡尔积、要单独定价备库存，在建品页现造一个
   * 只对这一件商品成立的维度，等于给自己开一条以后对不上账的路 ——
   * 所以规格一律去「商品规格和参数」加一次，全店通用。
   * 而参数是写给买家看的一行字：「海拔 1200 米」平台不会替他想到，
   * 他也不该为了标一行字先跳出去一趟、回来再重填一遍这件货。
   *
   * <p><b>但加出来的东西是一样的</b>：走同一个 `mAddSpecDim(PROP)` 落进规格库、
   * 拿到编号、挂到这个类目下 —— 下次建同类的品它就在那儿了。
   * 「只在这一件商品上有效」的私有字符串一条都不造，那是掉出聚合的那条路。
   */
  const addingParam = ref(false);
  const newParam = ref("");

  /** 正在给哪个参数加值；null = 没在加 */
  const addingValueFor = ref<SpecTemplate | null>(null);
  const newParamValue = ref("");
  /** 平台在这个参数下的**全部**值。分成「能加的」与「已经在用的」两排，见 openParamValue */
  const paramPool = ref<SpecOption[]>([]);
  /** 池子没取到。**与「平台没配」必须分开说** —— 两者在界面上都是一片空白 */
  const paramPoolFailed = ref(false);

  /**
   * 打开「加可选值」。**取的是平台这一项的全部值，不是「减去已有的」之后那点余数。**
   *
   * <p>此前这里直接把 `全部 − 已有` 存进 `paramCands`，于是当类目已经把平台该项的值
   * 全给了（「产地」平台就三个值，类目全给了），候选恒为空 —— 弹层里只剩一个
   * 「新建可选值」输入框，**看不到系统里有哪些值，也没有一句话说为什么**。
   * 商家的描述是「无法添加系统中的值」，而他看到的确实就是这样。
   *
   * <p>现在池子整份留着，由下面三个 computed 分成「能加的」与「已经在用的」，
   * 两排都摆出来 —— **「系统里有什么」和「你还能加什么」是两个问题**，
   * 只回答后者的话，前者就永远无解。
   */
  async function openParamValue(d: SpecTemplate) {
    addingValueFor.value = d;
    newParamValue.value = "";
    paramPool.value = [];
    paramPoolFailed.value = false;
    try {
      paramPool.value = await api.mDimValues(d.templateNo);
    } catch {
      // 吞掉异常会让「取不到」和「平台没配」长成同一屏 —— 那句话就成了假话
      paramPoolFailed.value = true;
    }
  }

  /** 这一项当前已经能选的值（类目给的 + 他加过的） */
  const paramHave = computed(
    () => new Set((addingValueFor.value?.options ?? []).map((o) => o.code ?? o.label)),
  );
  /** 平台有、这一类还没有的 —— 点一下就用上 */
  const paramCands = computed(() => paramPool.value.filter((o) => !paramHave.value.has(o.code ?? o.label)));
  /** 平台有、上面那排已经列着的。**照样摆出来**：他要确认的是「系统里有没有」 */
  const paramUsed = computed(() => paramPool.value.filter((o) => paramHave.value.has(o.code ?? o.label)));

  /*
   * 副标题要说当下这一屏的实话。**四种情况说四句** —— 此前是三种，
   * 而漏掉的那一种恰恰是最常见的：平台有值、但都已经在上面了。
   * 那一屏此前不说话，于是看起来和「平台什么都没配」一模一样。
   *
   *   取不到       → 「没取到平台的可选值，重开一次」  ← 此前被 catch 吞成「没配」
   *   有能加的     → 「平台可选值」
   *   池子非空但全在用 → 「平台这一项的值都已经在上面了」  ← 此前不说话
   *   池子是空的   → 「该参数暂无平台可选值」
   *
   * 一律写死一句的话，总有一屏在说假话 —— 而假话比没话更贵。
   */
  const paramSheetHint = computed(() => {
    if (paramPoolFailed.value) return t("goods.paramPoolFailed");
    if (paramCands.value.length) return t("goods.paramMore");
    if (paramPool.value.length) return t("goods.paramAllAdded");
    return t("goods.paramFillHint");
  });

  function closeParamValue() {
    addingValueFor.value = null;
    newParamValue.value = "";
    paramPool.value = [];
    paramPoolFailed.value = false;
  }

  /**
   * 挑一个平台已有的值。**只落在这件货身上，不改本店配置。**
   *
   * <p>他这一下的意思是「这袋菜是云南的」，不是「以后蔬菜这一类都要有云南这一档」。
   * 顺手把它写进类目覆盖的话，全店所有蔬菜的参数列表都跟着变了 ——
   * 而他从没这么说过。要改那个，去「商品规格和参数」，那里的每一下都是全店的。
   */
  function pickParamCand(o: SpecOption) {
    const d = addingValueFor.value;
    if (!d) return;
    pickParam(d, o);
    closeParamValue();
  }

  async function confirmAddParam() {
    const name = newParam.value.trim();
    if (!name || !categoryNo.value) return;
    try {
      const dim = await api.mAddSpecDim(name, [], "PROP");
      /*
       * **挂到这个类目下**，否则它只是躺在规格库里：下次进来这一页看不到它，
       * 而他明明刚建过 —— 与「我的规格」里加一个是同一条路，所以用同一个载荷拼装。
       *
       * <p>当前状态从**这两条按类目取的接口**拿，不从「本店货架类目」那份拿：
       * 这件货的类目不一定在他的货架上（货架是他摆出来卖的那几类，
       * 而建品页可以选到任何类目）。拿不到卡就静静不保存 —— 加完什么都没发生，
       * 而这条路上没有任何东西会报错。实测就是这么撞上的（蔬菜不在货架上）。
       *
       * <p>先取一份当前状态是因为后端先清后写：少带一条就抹掉一条。
       */
      const [dims, props] = await Promise.all([
        api.mSpecTemplates(undefined, categoryNo.value).catch(() => []),
        api.mSpecProps(categoryNo.value).catch(() => []),
      ]);
      await api.mSaveSpecOverride(
        categoryNo.value,
        buildSpecOverride({
          g: { categoryNo: categoryNo.value, categoryName: "", dims, props },
          added: dim,
        }),
      );
      await loadProps();
      addingParam.value = false;
      newParam.value = "";
      // 撞上平台已有的同名参数时后端直接返回它 —— 说一声，否则他以为自己白填了
      if (dim.name !== name) {
        uni.showToast({ title: t("mySpecs.valueMerged", { name: dim.name }), icon: "none" });
      }
    } catch (e) {
      uni.showToast({ title: (e as Error).message, icon: "none" });
    }
  }

  /**
   * 给一个没有候选值的参数填一个值。
   *
   * <p><b>填的是规格库里的一档，不是这件货身上的一个字符串。</b>
   * 「海拔」这种量纲型平台不会枚举值，但他填的「1200 米」仍然要拿到编号 ——
   * 否则三家店的「1200米」「1200 m」「一千二」永远聚不到一起，
   * 而那正是养这个库的全部理由。落库之后它也成了下一件货的候选。
   */
  async function confirmParamValue() {
    const d = addingValueFor.value;
    const text = newParamValue.value.trim();
    if (!d || !text) return;
    try {
      const added = await api.mAddSpecValue(d.templateNo, text);
      await loadProps();
      const fresh = propDims.value.find((x) => x.templateNo === d.templateNo) ?? d;
      pickParam(fresh, { code: added.code || added.valueNo, label: added.label });
      closeParamValue();
    } catch (e) {
      uni.showToast({ title: (e as Error).message, icon: "none" });
    }
  }

  /** 点一下选中/取消。**再点一次取消** —— 不给「清空」按钮，一排 chip 自己就是开关 */
  function pickParam(dim: SpecTemplate, o: SpecOption) {
    const cur = paramValues.value[dim.templateNo];
    if (cur && cur.label === o.label) {
      const next = { ...paramValues.value };
      delete next[dim.templateNo];
      paramValues.value = next;
      return;
    }
    paramValues.value = {
      ...paramValues.value,
      /*
       * **不填 valueNo。** 端上手里只有 code —— 它才是跨店可比的那个稳定编码，
       * valueNo 是库里的行号。伪造一个行号发上去，后端存下来就是一条对不上的引用。
       * 真要它的话该由后端按 dimNo + code 反查（与规格值那侧的 resolveValueNos 同一条路）。
       */
      // name 一起存：买家页要显示「产地：本地」，只有 dimNo 的话那一行是 `SD_ORIGIN: 本地`。
      // 与 specGroups 的组名同一口径 —— 快照，商家事后改本店叫法不影响已建好的商品
      [dim.templateNo]: { dimNo: dim.templateNo, name: dim.name, code: o.code, label: o.label },
    };
  }


  return {
    propDims, paramValues, loadProps,
    addingParam, newParam, addingValueFor, newParamValue,
    paramPool, paramPoolFailed, openParamValue, paramHave, paramCands, paramUsed,
    paramSheetHint, closeParamValue, pickParamCand, confirmAddParam, confirmParamValue, pickParam,
  };
}
