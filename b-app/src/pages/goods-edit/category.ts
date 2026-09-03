// 商品编辑页的**类目选择**：父子两级 + 最近用过 + 类目门槛提示。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么单独一个文件
// ─────────────────────────────────────────────────────────────────────────────
// 与 `price-rows.ts` / `photos.ts` 同一次拆分。类目这一块的状态（树、已选路径、
// 最近用过）与规则（哪一支能选、面包屑怎么写）自成一体，
// **唯一跨出去的是「选中之后要连带做什么」** —— 定商品形态、重取规格模板与参数。
// 那件事牵着规格与履约两块，所以留在页面，这里只回调一次。
//
// <p>搬过来的实现一个字没改。
import { computed, ref } from "vue";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { SHOW_CATEGORY_GATE } from "@/shared/flags";

/**
 * 端上允许挂商品的类目模板。**虚拟与卡券建不了** —— 树上那两支直接砍掉，
 * 而不是选了再拦：选完才说「这类不能建」，等于让人白走一趟。
 */
const ALLOWED_TEMPLATES = ["STANDARD", "FRESH", "SERVICE"];
import type { Category } from "@shared/types";

/**
 * 类目选择的全部状态与动作。
 *
 * @param onSelected 选定叶子类目之后做什么（定商品形态、重取模板/参数）——
 *                   那一步要动规格与履约，属于页面的事
 */
export function useCategoryPicker(onSelected: (leaf: Category) => Promise<void>) {
  const merchant = useMerchantStore();

  const categoryTree = ref<Category[]>([]);
  const categoryNo = ref("");
  /** **已选**的路径，[一级, 二级]；只选到一级也允许。面包屑与提交都取它 */
  const catPath = ref<Category[]>([]);

  /**
   * 当前展开的一级类目 —— **与「已选」分开的两个状态**。
   *
   * <p>翻着看不等于改了选择：商家点开「食品生鲜」看了一眼又回到「日用百货」，
   * 已选的那一项不该被清掉。
   */
  const parentNo = ref("");

  /**
   * 二级候选。平台类目**就两级**（V168），所以这一行永远是最后一行 ——
   * 此前是逐级下钻的弹层，一次只看得见一层，改个类目要连点返回往上爬；
   * 两级平铺之后，父与子同屏，改哪一级都是一下。
   */
  const children = computed<Category[]>(
    () => categoryTree.value.find((c) => c.categoryNo === parentNo.value)?.children ?? [],
  );

  /**
   * 这个类目商家**能不能选**。
   *
   * <p>判据与后端一致：无门槛，或主体持有那张码。端上先说清楚 ——
   * 让他选完、填完一屏、点保存才被拒，是最差的一种拒绝，
   * 而那句「你还没有资质授权」既说不出缺哪张证，也说不出去哪申请。
   *
   * <p><b>仍然可选，只是标出来</b>：草稿归到一个还没批下来的类目下是合法的，
   * 他可能正准备去申请 —— 真正拦在上架那一刻（后端闸一）。
   */
  function gateOf(c: Category) {
    const code = c.requiredCode;
    if (!code) return null;
    return {
      granted: merchant.categoryCodes.includes(code),
      qualification: (c.qualifications ?? []).join("、"),
    };
  }

  /** 已选类目的门槛。选完才提示 —— 见 gateOf 的说明 */
  const pickedGate = computed(() => {
    const leaf = catPath.value[catPath.value.length - 1];
    return leaf ? gateOf(leaf) : null;
  });

  /** 面包屑：「食品生鲜 / 蔬菜」。没选时为空，由占位文案顶上 */
  const categoryLabel = computed(() =>
    catPath.value.length ? catPath.value.map((c) => c.name).join(" / ") : "",
  );

  /**
   * 选一级。
   *
   * <p><b>只展开，不改已选</b> —— 除非这一级底下没有二级（服务类目本来就只有两级，
   * 硬凑一层是为了对齐而对齐）。那种情况下它自己就是终点，直接选中。
   */
  function pickParent(c: Category) {
    parentNo.value = c.categoryNo;
    if (!c.children?.length) void select([c]);
  }

  /**
   * 「最近用过」的类目。**本地记录，不占后端**：一家店的货高度集中，
   * 建第二个商品时要选的那一档多半就在这三五个里 —— 一点就换。
   *
   * <p>它同时是「识别没填对」和「压根没识别出来」两种情况下最快的入口，
   * 所以不藏起来，就摆在一级类目上面那一行。
   */
  const RECENT_CATS_KEY = "biz.recentCategories";

  /*
   * 【已移除】specOpen / paramOpen —— 规格与参数现在**常驻展开**。
   *
   * <p>收起态是为了替不分规格的商家省一屏，但账压在了另一头：
   * 他看不见这一类到底有哪些规格，而「还能按什么分」恰恰是建品时最难的一步 ——
   * **藏起来的东西不会被想起来**。省下的一屏，代价是他要等买家问
   * 「有没有大份的」才发现自己漏了。
   *
   * <p>「记住他上次是开是合」更糟：同一个类目，换台手机就是另一套默认，
   * 而他不知道为什么。默认值要能被解释，本机记忆解释不了。
   */
  const recentCats = ref<{ categoryNo: string; name: string }[]>([]);

  function loadRecentCats() {
    try {
      const raw = uni.getStorageSync(RECENT_CATS_KEY);
      recentCats.value = Array.isArray(raw) ? raw.slice(0, 5) : [];
    } catch {
      recentCats.value = [];
    }
  }

  /** 保存成功后记一笔。同一个类目再选只是提前，不重复入列 */
  function rememberCat() {
    const leaf = catPath.value[catPath.value.length - 1];
    if (!leaf) return;
    const next = [
      { categoryNo: leaf.categoryNo, name: leaf.name },
      ...recentCats.value.filter((c) => c.categoryNo !== leaf.categoryNo),
    ].slice(0, 5);
    recentCats.value = next;
    try {
      uni.setStorageSync(RECENT_CATS_KEY, next);
    } catch {
      // 存不进去不影响建品，最近用过下次不显示而已
    }
  }

  /** 点「最近用过」：按编号回原树找路径，连面包屑与形态一起落 */
  function pickRecent(no: string) {
    const path = findPath(categoryTree.value, no);
    if (!path.length) {
      // 类目被运营下架了：静默从最近列表里摘掉，不给一个点了没反应的 chip
      recentCats.value = recentCats.value.filter((c) => c.categoryNo !== no);
      return;
    }
    void select(path);
  }

  /** 选二级。到这里就是终点，不再往下 */
  function pickChild(c: Category) {
    const parent = categoryTree.value.find((x) => x.categoryNo === parentNo.value);
    void select(parent ? [parent, c] : [c]);
  }

  /**
   * 落选 —— **全页唯一一处写 `categoryNo` 与 `type`**。
   *
   * <p>类目在库里就带着 `template`（STANDARD/FRESH/SERVICE/VOUCHER/VIRTUAL），
   * 它与品类是同一件事的两套码。此前页面上另有一排品类 chip，商家把同一件事
   * 填两遍，而且**可以互相矛盾**：选「叶菜」类目配「日用品」品类，没有一处会拦，
   * 直到下单时才因为履约方式不对而出问题（生鲜要截单、服务不发货）。
   *
   * <p>现在 chip 已经删掉，`type` 只是个**展示值** —— 后端也不采信请求里的 type，
   * 它自己按 categoryNo 查一遍。两边同源，端上算错也写不进库。
   */
  async function select(path: Category[]) {
    const leaf = path[path.length - 1];
    if (!leaf) return;
    catPath.value = path;
    parentNo.value = path[0]?.categoryNo ?? leaf.categoryNo;
    categoryNo.value = leaf.categoryNo;
    await onSelected(leaf);
    /*
     * **类目一确定就重取模板** —— 不再只在品类变了的时候取。
     *
     * 只传品类拿回来的是兜底那批（STANDARD 一个盖住 18 个二级类目：手机数码
     * 与鲜花共用「包装：袋装/瓶装/罐装」，等于没有推荐）。类目级模板才有信息量，
     * 而它只有带上 categoryNo 才拿得到。
     */

    /*
     * **不再自动建规格组。**
     *
     * <p>档位改成「一个都不预选」之后，自动建出来的是一个空壳：占着一张展开的卡，
     * 里面一排灰着的档位，而他还没说这件货要不要分档。
     * 现在收起态直接摆候选（＋重量 ＋包装 …），他点一个才成组 ——
     * 少一次「先撤销系统替我做的事」。
     */
  }

  /** 按 categoryNo 还原选择路径（回显已有商品时用） */
  function findPath(nodes: Category[], target: string, trail: Category[] = []): Category[] {
    for (const n of nodes) {
      const next = [...trail, n];
      if (n.categoryNo === target) return next;
      const hit = findPath(n.children ?? [], target, next);
      if (hit.length) return hit;
    }
    return [];
  }

  /** 拉本店货架。取不到不该挡住建品：那时退回全量类目树，与改版前一样 */
  async function loadCategories() {
    // 取不到不该挡住整个编辑页：拿不到就退化成「不归类」，商品照样存得下
    categoryTree.value = prunable(await api.mCategoryTree().catch(() => []));
  }

  /**
   * 砍掉商家自助建不了的那几支。
   *
   * <p>这条规则原先长在品类 chip 上（"一期只开 NORMAL/FRESH/SERVICE 三个"）——
   * 品类改成由类目派生之后，它必须跟着搬到**类目树**上：留着虚拟/卡券的类目，
   * 商家选进去就得到一个建了也卖不出去的商品，而形态那行还会理直气壮地写着「卡券」。
   *
   * <p>按 `template` 砍而不是按名字：类目名运营随时可改，模板是判据。
   * 一级砍掉整支 —— 虚拟与卡券在树上本来就是独立的一级分支。
   */
  function prunable(tree: Category[]): Category[] {
    return tree.filter((c) => !c.template || ALLOWED_TEMPLATES.includes(c.template));
  }

  return {
    categoryTree, categoryNo, catPath, parentNo, children, gateOf, pickedGate, categoryLabel,
    pickParent, recentCats, loadRecentCats, rememberCat, pickRecent, pickChild, select,
    findPath, loadCategories,
  };
}
