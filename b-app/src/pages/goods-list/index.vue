<script setup lang="ts">
// 商品列表（B-11.3.5 / 3.6）。上下架与改库存是高频操作，做在列表行里，
// 不进详情页 —— 店主蹲在货架前改库存，不该点三层。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onReachBottom, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { takeGoodsCategory } from "@/shared/handoff";
import { SHOW_CATEGORY_GATE } from "@/shared/flags";
import { money } from "@shared/utils/money";
import type { Category, Goods, GoodsStatus, StoreCategory } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

/*
 * 页签。**此前只有三个** —— 而 `status` 是四态：
 * 被驳回的商品商家筛不出来，只能在「全部」里一条条翻，
 * 而那恰恰是最需要他去处理的一批（改完才能重新提交）。
 */
const TABS: { key: GoodsStatus | "OUT_OF_STOCK" | ""; labelKey: string }[] = [
  { key: "", labelKey: "common.all" },
  { key: "ON_SALE", labelKey: "goods.statusON_SALE" },
  // 草稿排在待审前面：它是**等商家自己动手**的那一批，而待审是在等平台
  { key: "DRAFT", labelKey: "goods.statusDRAFT" },
  { key: "PENDING", labelKey: "goods.statusPENDING" },
  { key: "REJECTED", labelKey: "goods.statusREJECTED" },
  { key: "OFF_SALE", labelKey: "goods.statusOFF_SALE" },
  /*
   * 缺货（B-4.1 要求的第三筛，一直没实现）。
   *
   * **它与上面四个不是同一轴**：那四个是「审核结果 × 上下架」，缺货是库存算出来的，
   * 一件在售商品照样能全规格断货。放在同一排页签里是因为商家的心智就是
   * 「我要看哪一批货」，而不是「我要按哪个维度筛」。
   */
  { key: "OUT_OF_STOCK", labelKey: "goods.statusOUT_OF_STOCK" },
];

const tab = ref<GoodsStatus | "">("");
const list = ref<Goods[]>([]);
/**
 * 翻页。**后端一页最多 50 条**（`Math.min(size, 50)`），而这里原先写死
 * `size: 50` 且从不翻页 —— 商品超过 50 个的商家**永远只能看到 50 个**，
 * 且界面上没有任何迹象表明还有别的：没有页码、没有「加载更多」、拉到底就没了。
 *
 * 实测：194 条商品的账号，列表停在第 50 条，剩下 144 条在 B 端不存在。
 * 这个缺陷只有在真实数据量下才看得见 —— 四条种子数据时它完全正常。
 */
const page = ref(1);
const hasMore = ref(false);

/**
 * 按标题搜。**服务层一直支持，端点此前写死传 null**，所以这一页从来没有搜索 ——
 * 商品少时看不出来，194 条的账号找一个商品要滚三十屏。
 *
 * 防抖 300ms：每敲一个字发一次请求，既费流量又会让结果乱序回来
 * （后发的先到，界面上闪一下又变回去）。
 */
const keyword = ref("");
let searchTimer: ReturnType<typeof setTimeout> | undefined;
function onSearch(v: string) {
  keyword.value = v;
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => void load(), 300);
}
function clearSearch() {
  keyword.value = "";
  void load();
}
const loading = ref(false);

const empty = computed(() => !loading.value && !list.value.length);

/**
 * 这一行到底是什么状态。
 *
 * **不能只看 `onSale`**：新建和每次改动都会回到审核中，那时 `onSale` 是 false，
 * 照布尔值渲染就成了「已下架」+ 一个必然失败的「上架」。
 * 后端下发的 `status` 才是四态（PENDING / REJECTED / ON_SALE / OFF_SALE）；
 * 老数据没有这个字段时回落布尔值。
 */
function stateOf(g: Goods) {
  return g.status ?? (g.onSale ? "ON_SALE" : "OFF_SALE");
}

/** 审核中或被驳回 —— 这两种状态下商家自己按不了上架 */
function pending(g: Goods) {
  const s = stateOf(g);
  return s === "PENDING" || s === "REJECTED";
}

/**
 * @param more true = 追加下一页；false/省略 = 从第一页重来（切页签、切门店、改完数据）
 */
/**
 * 类目筛 = **这家店自己摆的货架**（门店类目），不是平台的全量类目树。
 *
 * <p><b>此前给的是一级类目，而那样一件也筛不出来</b>：商品挂的是二级类目
 * （goods-edit 选的就是二级），后端 `GET /biz/goods` 的 categoryNo 是
 * **精确匹配**（`eq`，不含子级）—— 拿「食品生鲜」去筛挂在「粮油调味」下的货，
 * 结果恒为空，而界面上看起来只是「这个类目没货」。
 *
 * <p>换成门店类目还顺带对齐了商家的心智：他在「我的类目」里摆了几个货架，
 * 商品列表就按那几个筛。平台有而他没摆的类目，本来就不该出现在他的工具栏里。
 */
const storeCategories = ref<StoreCategory[]>([]);
const categoryNo = ref("");

/** 平台全量树：只用来判「这件货的类目本店有没有资质」，不进筛选条 */
const rootCategories = ref<Category[]>([]);

async function loadCategories() {
  // 取不到不该挡住列表：筛选是锦上添花，商品列表本身要照常出来
  const [tree, mine] = await Promise.all([
    api.mCategoryTree().catch(() => [] as Category[]),
    /*
     * **先判 `biz:store` 再发**：这一页的门禁是 `biz:stock`（改库存是店员的日常），
     * 而门店货架要 `biz:store` —— 店员与理货员进得来，却打不通这个请求。
     * 不判的话他们每次进商品页都吃一个 70006，而「本店类目」那一段本来就该对他们不存在。
     */
    merchant.can("biz:store")
      ? api.mStoreCategories(merchant.storeNo || "default").catch(() => [] as StoreCategory[])
      : Promise.resolve([] as StoreCategory[]),
  ]);
  rootCategories.value = tree;
  storeCategories.value = mine;
  // 切店之后原来的筛选可能已经不在这家店的货架上了，留着它列表会一直是空的
  if (categoryNo.value && !mine.some((c) => c.categoryNo === categoryNo.value)) {
    categoryNo.value = "";
  }
}

/**
 * 类目编号 → 类目节点的**平铺索引**。
 *
 * <p>`mCategoryTree()` 返回的是树，而列表里每一行只有 `categoryNo` ——
 * 逐行去树里递归查是 O(行 × 树)，一屏 50 行就是几千次比较。
 */
const categoryIndex = computed<Map<string, Category>>(() => {
  const m = new Map<string, Category>();
  const walk = (list: Category[]) => {
    for (const c of list) {
      m.set(c.categoryNo, c);
      if (c.children?.length) walk(c.children);
    }
  };
  walk(rootCategories.value);
  return m;
});

/**
 * 这件商品**缺不缺资质**。缺 = 它现在点「上架」必被后端拒（70002）。
 *
 * <p>为什么要在列表页算：门槛卡在**上架**那一刻，而列表页此前没有任何迹象 ——
 * 商家只能一条一条点上架去撞。线上实测 M0001 有 138 件货处在这个状态，
 * 分布在 9 个类目里，一件都上不了架，而列表上看不出任何区别。
 *
 * <p>判据与后端 `requireCategoryAuthorized` 一致：类目挂了 `requiredCode`
 * 且主体没持有它。没归类的商品不算缺 —— 那是另一件事（后端也放行）。
 *
 * @returns null = 不缺；否则给出人读的资质名，用来说「缺哪张」
 */
function gateOf(g: Goods): string | null {
  const c = g.categoryNo ? categoryIndex.value.get(g.categoryNo) : undefined;
  const code = c?.requiredCode;
  if (!code || merchant.categoryCodes.includes(code)) return null;
  return (c?.qualifications ?? []).join("、") || code;
}

/** 本页缺资质的件数。**只统计当前已加载的**，不谎称是全店总数 */
const gatedCount = computed(() => list.value.filter((g) => gateOf(g) !== null).length);

function switchCategory(no: string) {
  categoryNo.value = categoryNo.value === no ? "" : no;
  void load();
}

async function load(more = false) {
  if (!merchant.isActive) return;
  if (more && (!hasMore.value || loading.value)) return;
  loading.value = true;
  try {
    const next = more ? page.value + 1 : 1;
    const res = await api.mGoodsList({
      status: tab.value || undefined,
      keyword: keyword.value.trim() || undefined,
      categoryNo: categoryNo.value || undefined,
      page: next,
      size: PAGE_SIZE,
    });
    list.value = more ? [...list.value, ...res.records] : res.records;
    page.value = next;
    // 拿满一页就认为还有下一页 —— 比信任 total 稳：total 与 records 的口径
    // 在按门店裁剪的场景下会分岔，而「这一页满了」是端上能自己看见的事实
    hasMore.value = res.records.length >= PAGE_SIZE;
  } finally {
    loading.value = false;
  }
}

/** 与后端上限同一个数。写 100 也只会拿回 50，而端上会以为「没有下一页了」 */
const PAGE_SIZE = 50;

onReachBottom(() => void load(true));

function switchTab(key: GoodsStatus | "") {
  tab.value = key;
  void load();
}

async function toggle(g: Goods) {
  /*
   * 上架前先在端上说清楚。**只拦上架，不拦下架** ——
   * 缺资质的商品要能下架（它可能是资质过期前上的架）。
   *
   * 不这样做的话商家看到的是后端那句通用错误，既说不出缺哪张证，
   * 也说不出是类目的问题 —— 他会反复回去改商品信息，而问题不在商品上。
   *
   * **闸门关着时这一段整个不走**（运营端开关，走 /biz/context）：后端此刻会放行，
   * 端上再拦就成了「点不动一个其实能按的按钮」，而且他无从知道为什么。
   */
  const need = merchant.categoryGateEnforced && !g.onSale ? gateOf(g) : null;
  if (need) {
    uni.showToast({ title: t("goods.gateBlocked", { s: need }), icon: "none" });
    return;
  }
  try {
    await api.mToggleGoods(g.goodsNo, !g.onSale);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 改库存（B-11.3.6）。
 * 单独给快捷入口而不是让店主进编辑页：**这是最高频的日常动作** ——
 * 一批菜卖完了要马上改数量，走完整表单（品类、规格、价格…）等于每次重填一遍。
 *
 * 多规格商品有多个 SKU，改哪个说不清楚，所以只对单规格开快捷入口，
 * 多规格仍进编辑页 —— 与其猜错，不如把话说明白。
 */
async function editStock(g: Goods) {
  if (g.skus.length > 1) {
    uni.showToast({ title: t("goods.multiSkuStock"), icon: "none" });
    uni.navigateTo({ url: `${ROUTES.goodsEdit}?goodsNo=${g.goodsNo}` });
    return;
  }
  const sku = g.skus[0];
  if (!sku) return;

  const value = await new Promise<string>((resolve) => {
    uni.showModal({
      title: t("goods.editStock"),
      editable: true,
      placeholderText: String(sku.stock),
      success: (r) => resolve(r.confirm ? (r.content ?? "") : ""),
      fail: () => resolve(""),
    });
  });
  if (!value.trim()) return; // 空输入 = 取消

  const n = Number(value.trim());
  // 负数与非数字要挡住 —— 库存写成 -5 之后 C 端的置灰与到货提醒逻辑全乱
  if (!Number.isFinite(n) || n < 0) {
    uni.showToast({ title: t("goods.stockInvalid"), icon: "none" });
    return;
  }

  try {
    /*
     * 多店走门店库存，单店走主体库存。
     * 不分的话，多店商家改完发现页面数字没变 —— 他改的是主体总量，
     * 而页面显示的是当前门店的数（后端按店取），两个数各走各的。
     */
    if (merchant.multiStore) {
      await api.mSaveStoreStock(g.goodsNo, sku.skuNo, Math.floor(n));
    } else {
      await api.mSaveStock(g.goodsNo, sku.skuNo, Math.floor(n));
    }
    uni.showToast({ title: t("common.saved"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 改**本店价**（多门店）。
 *
 * <p>与改库存同一个形状，但**回退方向相反**：清空 = 回到主体价，而不是 0。
 * 这条要有 —— 没有它，商家给某店定过价之后就再也回不去，「改成和总部一样」
 * 只能靠他自己抄一遍数字，而抄错没有任何一处会拦。
 */
async function editStorePrice(g: Goods) {
  if (g.skus.length > 1) {
    uni.showToast({ title: t("goods.multiSkuStock"), icon: "none" });
    uni.navigateTo({ url: `${ROUTES.goodsEdit}?goodsNo=${g.goodsNo}` });
    return;
  }
  const sku = g.skus[0];
  if (!sku) return;

  const current = sku.storePrice ?? sku.price;
  const value = await new Promise<string>((resolve) => {
    uni.showModal({
      title: t("goods.editStorePrice"),
      content: t("goods.storePriceTip"),
      editable: true,
      placeholderText: money(current),
      success: (r) => resolve(r.confirm ? (r.content ?? "") : ""),
      fail: () => resolve(""),
    });
  });
  // 取消对话框与「清空输入框再确定」是两件事：前者什么都不做，后者是撤销本店价
  if (value === "") return;

  const raw = value.trim();
  const price = raw ? Math.round(Number(raw) * 100) : null;
  if (price !== null && (!Number.isFinite(price) || price < 0)) {
    uni.showToast({ title: t("goods.priceInvalid"), icon: "none" });
    return;
  }
  try {
    await api.mSaveStorePrice(g.goodsNo, sku.skuNo, price);
    uni.showToast({ title: t("common.saved"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 草稿 → 待审。重复点无副作用，所以不做本地防抖之外的额外拦截 */
async function submit(g: Goods) {
  try {
    await api.mSubmitGoods(g.goodsNo);
    uni.showToast({ title: t("goods.submitted"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function edit(g?: Goods) {
  uni.navigateTo({ url: g ? `${ROUTES.goodsEdit}?goodsNo=${g.goodsNo}` : ROUTES.goodsEdit });
}

/** 总库存 = 各规格之和。单规格可就地改（editStock），多规格进编辑页逐个改 */
function stockOf(g: Goods) {
  return g.skus.reduce((s, k) => s + k.stock, 0);
}

/** 上次拉类目时是哪家店 —— 货架按店走，切店必须重拉，否则筛的是上一家店的货架 */
const catsOfStore = ref("");

onShow(() => {
  /*
   * 从「我的类目」点某一类过来时，**带着那一类落地**。
   *
   * 不带的话他看到的是全部商品，得自己在筛选条里再选一次刚点过的那个类目 ——
   * 这一跳的意义正在于省掉那一次。
   *
   * 取完即清（takeGoodsCategory 一次性）：留着的话，下次从 tab 图标进来
   * 还会莫名停在上次那个类目上，而界面上没有任何东西解释为什么。
   */
  const handed = takeGoodsCategory();
  if (handed) categoryNo.value = handed;

  // 平台树几乎不变，但门店货架会随「我的类目」的编辑与切店而变
  if (!rootCategories.value.length || catsOfStore.value !== merchant.storeNo) {
    catsOfStore.value = merchant.storeNo;
    void loadCategories();
  }
  void load();
});
</script>

<template>
  <!--
    列表本身要 `biz:stock`（`/biz/goods`）。**它是 tabBar 四页之一**，
    客服与配送员没有这个码 —— 不判的话他们每天点一次「商品」，每天吃一个 70006 toast，
    而页内那几个按钮反倒早就按 can() 裁好了。门禁漏的偏偏是列表这一件必做的事。
  -->
  <sh-scaffold title-key="goods.title" tab="goods" :denied="!merchant.can('biz:stock')">
    <!--
      **搜索提到第一行，状态页签独占整宽。**

      此前这两者挤在同一行：六个状态页签本来就要横滚，右边那截「＋ 新建商品」
      又固定占掉约 96rpx —— 两个都不舒服，而最后一个状态常常划不到。
      新建改成右下悬浮按钮（见 .fab）之后，这一行就还给了页签。

      搜索排在最前，是因为商品一多，「找某一个」比「筛一批」高频得多。
    -->
    <view class="search">
      <input
        class="search__input"
        :value="keyword"
        :placeholder="$t('goods.searchPh')"
        confirm-type="search"
        @input="onSearch(String(($event as any).detail.value ?? ''))"
      />
      <text v-if="keyword" class="search__clear" @tap="clearSearch">✕</text>
    </view>

    <view class="bar">
      <!-- 必须套一层容器：sh-tabs 是**多根组件**（v-if/v-else 两个根），
           Vue 3 下 class 无法透传到 fragment 根上，写在组件标签上会被静默丢弃 -->
      <view class="bar__tabs">
        <sh-tabs
          :items="TABS.map((t) => ({ key: t.key, label: String($t(t.labelKey)) }))"
          :active="tab"
          @change="switchTab"
        ></sh-tabs>
      </view>
    </view>

    <!--
      缺资质汇总。**只在真有的时候出现**，且说清是「当前列表里」的数 ——
      分页只加载了一部分，把它说成全店总数是在编一个自己也不知道的数字。
    -->
    <text v-if="SHOW_CATEGORY_GATE && gatedCount" class="gate-sum">
      {{ $t("goods.gateCount", { n: gatedCount }) }}
    </text>

    <!-- 一级类目筛。只有一个类目时不显示 —— 那时它是个恒真的开关 -->
    <scroll-view v-if="storeCategories.length > 1" class="cats" scroll-x>
      <view class="cats__row">
        <text
          v-for="c in storeCategories"
          :key="c.categoryNo"
          class="sh-chip cats__chip"
          :class="{ 'sh-chip--primary': categoryNo === c.categoryNo }"
          @tap="switchCategory(c.categoryNo)"
        >
          {{ c.name }}
        </text>
      </view>
    </scroll-view>

    <!--
      当前门店。**多店才显示** —— 单店商家看到「当前门店」只会疑惑还有别的店。
      不显示的代价是实测出来的：商家给某家店设了 1 件库存，商品页却显示主体总量 91，
      他会以为还有货。

      提示里那半句「没单独设过的门店按 0 卖」是后端的真实语义
      （`StockPortImpl.hasStoreStock`：任意一家店设过，这个 SKU 就整体转成按店算，
      没设的店按 0 —— 少卖可恢复，超卖不可）。真实链路上验过：
      在新店设了 5 件，主店那 80 件当场变成 0 —— **不写出来的话没人能预料到**。
    -->
    <!-- 当前门店只读标记（库存按店）：切店在「我的」 -->
    <biz-store-tag></biz-store-tag>

    <!--
      空状态只说事实，不再放「新建第一个商品」——
      右下角那个常驻悬浮按钮已经是新建入口，同一屏两个一模一样的主色按钮
      只会让人怀疑它们做的不是同一件事。
    -->
    <sh-empty v-if="empty" :text='$t("goods.empty")'></sh-empty>

    <!--
      **一行商品分成上下两段，不再是「左信息 / 右操作」两栏。**

      两栏在真实数据上塌了：多门店时右栏有四个按钮（编辑/上架/改库存/本店价），
      按钮把左栏挤到几十 px 宽 —— 商品名只剩一个字、价格与库存换行叠在一起，
      而这正是这一页唯一需要一眼看清的东西。实测 375 宽下「五常大米 10斤装」
      显示成「五」。

      现在：上段是「图 + 名 + 价/库存 + 状态」，下段整宽放按钮并允许换行。
      按钮多一个少一个都不再影响上面那行的可读性。
    -->
    <view v-for="g in list" :key="g.goodsNo" class="sh-card row">
      <view class="row__top">
        <sh-cover class="row__cover" :src="g.cover"></sh-cover>
        <view class="row__main">
          <text class="row__title">{{ g.title }}</text>
          <view class="row__meta">
            <text class="row__price sh-num">{{ money(g.price) }}</text>
            <text class="row__stock sh-num" :class="{ 'is-out': stockOf(g) === 0 }">
              {{ $t("goods.stock") }} {{ stockOf(g) }}
            </text>
          </view>
        </view>
        <view class="state" :class="'state--' + stateOf(g)">
          <text class="state__dot"></text>
          <text class="state__txt">{{ $t(`goods.status${stateOf(g)}`) }}</text>
        </view>
      </view>
      <view class="row__ops">
        <!--
          驳回 / 强制下架的理由。**没有它，商家面对「已驳回」只能猜要改什么** ——
          审计日志只有运营看得到。后端一直在发这个字段，端上此前连声明都没有。
        -->
        <text v-if="g.auditReason" class="reason">{{ g.auditReason }}</text>
        <!--
          缺资质。放在状态那一列而不是标题旁边：它回答的是
          「这件货为什么上不了架」，属于状态，不是商品属性。
        -->
        <text v-if="SHOW_CATEGORY_GATE && gateOf(g)" class="reason reason--gate">
          {{ $t("goods.gateRow") }}
        </text>
        <view class="row__btns">
          <!-- 编辑与上下架都会改价/改可见性 → biz:goods；改库存只是数量 → biz:stock。
               这条缝就是店员的权限边界：卖完了能马上改数，但改不了价 -->
          <text v-if="merchant.can('biz:goods')" class="mini" @tap="edit(g)">
            {{ $t("goods.edit") }}
          </text>
          <!-- 草稿只给「提交审核」：上架按钮对它必被拒，而拒绝的理由（还没过审）
               对一个自己都没提交的商品说不通 -->
          <text
            v-if="g.status === 'DRAFT' && merchant.can('biz:goods')"
            class="mini"
            @tap="submit(g)"
          >
            {{ $t("goods.submit") }}
          </text>
          <!-- 审核中/已驳回时**不给上架按钮**：后端必拒（70003），
               留着它等于给商家一个永远点不动的按钮，而错在哪一句话都没有 -->
          <text
            v-if="merchant.can('biz:goods') && !pending(g) && g.status !== 'DRAFT'"
            class="mini"
            @tap="toggle(g)"
          >
            {{ g.onSale ? $t("goods.offSale") : $t("goods.onSale") }}
          </text>
          <text v-if="merchant.can('biz:stock')" class="mini" @tap="editStock(g)">
            {{ $t("goods.editStock") }}
          </text>
          <!--
            本店价只在多门店时出现：单店商家改的就是主体价（编辑页那个），
            多给一个入口只会让他分不清自己改的是哪个数。
          -->
          <text
            v-if="merchant.multiStore && merchant.can('biz:goods')"
            class="mini"
            @tap="editStorePrice(g)"
          >
            {{ $t("goods.editStorePrice") }}
          </text>
        </view>
      </view>
    </view>

    <!--
      翻页反馈。**没有它，滚到底会以为「就这些了」** —— 而下一页可能正在路上。
      到底了也要说一声：194 条里滚到最后却什么提示都没有，人会怀疑是不是卡住了。
    -->
    <text v-if="loading && list.length" class="more">{{ $t("common.loading") }}</text>
    <text v-else-if="list.length && !hasMore" class="more">{{ $t("goods.noMore") }}</text>

    <!--
      新建商品。**建商品/改价属于 biz:goods**；店员只有 biz:stock，不显示这个入口。

      悬浮而不是嵌在顶部工具条里：那里的宽度要留给六个状态页签（它们本来就得横滚），
      而新建是低频高价值的动作 —— 拇指够得到、是全页唯一的主色实心块就够了。
      不放在导航栏右上：`sh-scaffold` 的标题栏在原生包里是系统导航栏，
      那个位置在 App / 小程序 / H5 三端不一致。
    -->
    <view v-if="merchant.can('biz:goods')" class="fab" @tap="edit()">
      ＋ {{ $t("goods.add") }}
    </view>
  </sh-scaffold>
</template>

<style scoped>
/* 搜索：贴着筛选条，不套卡片 —— 它是这一页的工具，不是一条内容 */
.search {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 0 24rpx;
  margin-bottom: 16rpx;
  background: var(--sh-surface);
  border-radius: 16rpx;
}
.search__input {
  flex: 1;
  height: 72rpx;
  font-size: 26rpx;
  color: var(--sh-ink);
}
.search__clear {
  padding: 0 8rpx;
  font-size: 28rpx;
  color: var(--sh-sub);
}
/* 翻页反馈：弱化到底，它是状态不是内容 */
.more {
  display: block;
  padding: 24rpx 0 8rpx;
  text-align: center;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.reason {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.4;
  text-align: right;
}
/* 缺资质：用警示色而不是危险色 —— 商品本身没错，缺的是一张证 */
.reason--gate {
  color: var(--sh-warning);
}
.gate-sum {
  display: block;
  padding: 16rpx 24rpx;
  font-size: 24rpx;
  line-height: 1.4;
  color: var(--sh-warning);
  background: var(--sh-warning-tint);
}
.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16rpx;
}
/*
 * 分栏横向可滚动（五个状态排不下）。作为 flex 子项，它默认按内容宽度撑开、
 * 溢出到「＋ 新建商品」底下，而且**因为自身盒子就等于内容宽度，反而滚不动** ——
 * 表现是最后一个 chip 被压掉半截且够不到，那个状态筛不了。
 *
 * `flex:1 + min-width:0` 给它一个确定且可收缩的宽度，滚动条件就成立了。
 * ⚠️ **不要再加 `overflow: hidden`** —— 试过，那会让 scroll-view 彻底滚不动
 * （裁是裁住了，但用户再也划不到后面的 chip，比溢出更糟）。
 */
/* 类目 chip 横向滚动：一级类目将来可能有七八个，换行会把工具栏顶成两行 */
.cats {
  white-space: nowrap;
  margin-top: 12rpx;
}
.cats__row {
  display: inline-flex;
  gap: 12rpx;
}
.cats__chip {
  font-size: 24rpx;
  padding: 10rpx 20rpx;
  /*
   * 两条都要，缺一个都会换行 —— 类目从 3 个扩到 6 个之后才显形：
   *
   * · `white-space`：uni 的 `<text>` 自带 `pre-line`，会**盖掉**父级 `.cats` 上的
   *   nowrap（实测 computed 就是 pre-line），于是「食品生鲜」断成两行
   * · `flex-shrink`：父级是 inline-flex 但被容器宽度框住，默认 shrink=1 时
   *   6 个 chip 会被压到 33px 宽而不是横向溢出滚动（实测 computed w=33.5px）
   */
  white-space: nowrap;
  flex-shrink: 0;
}
.bar__tabs {
  flex: 1;
  min-width: 0;
}
/*
 * 新建商品：右下悬浮。
 *
 * `position: fixed` 在 uni 的三端一致（小程序里也生效），
 * 底部留 128rpx 是给 tabBar 让位 —— 压在 tabBar 上的话，
 * 「商品」那个 tab 就点不着了。
 */
.fab {
  position: fixed;
  right: 32rpx;
  /*
   * 抬到 tabBar 上方一指宽。128rpx 时它几乎贴着菜单，拇指落点与「商品」那个
   * tab 只差几毫米 —— 想点新建却切了页。tabBar 自身高度约 130rpx（含安全区），
   * 再留 60rpx 的空当。
   */
  bottom: calc(190rpx + env(safe-area-inset-bottom));
  z-index: 10;
  padding: 20rpx 36rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 28rpx;
  font-weight: 600;
  white-space: nowrap;
  /* 阴影用 scrim（皮肤里那层半透明黑）：写死 rgba 在深色皮肤下会糊成一团 */
  box-shadow: 0 8rpx 24rpx var(--sh-scrim);
}
/* 列表密度对齐 C 端（平台版式约定）：卡片之间只留一条缝。
   商家一天要扫几十次这类列表，行距每多 10rpx，一屏就少一行。 */
.row {
  margin-bottom: 14rpx;
}
/* 上段：图 + 名/价 + 状态。状态贴右，名字吃掉中间所有剩余宽度 */
.row__top {
  display: flex;
  gap: 20rpx;
  align-items: center;
}
.row__cover {
  font-size: 60rpx;
  width: 96rpx;
  height: 96rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  text-align: center;
  line-height: 96rpx;
}
.row__main {
  flex: 1;
  min-width: 0;
}
/*
 * **标题是这一页的识别锚点，要压过价格。**
 * 原先价格 30rpx/700 深红、标题 28rpx/600（字阶只到 30，所以标题取 30、价格降到 26） —— 商家扫列表是在找「哪个商品」，
 * 最抢眼的却是它的价格。维护页与 C 端商品卡的重点本来就相反：
 * 那边卖东西，价格该跳出来；这边管东西，名字才是入口。
 *
 * 单行省略：长名换行会把卡片撑高，一屏少一行 —— 194 条的列表里，
 * 每屏少一行就是多滚五屏。
 */
.row__title {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  color: var(--sh-ink);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.row__meta {
  display: flex;
  gap: 20rpx;
  align-items: baseline;
  margin-top: 8rpx;
}
/* 降到属性档：仍是深红（可读性由 primary-text 保证），但不再抢标题的位 */
.row__price {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
}
.row__stock {
  font-size: 26rpx;
  color: var(--sh-sub);
}
/* 卖完了要一眼扫得到 —— 它是「今天要干的活」，而 0 和 180 现在长得一样 */
.row__stock.is-out {
  color: var(--sh-danger);
  font-weight: 600;
}
/* 状态：色点 + 文字，**无底色** —— 与动作按钮在形态上分开。
   原先它和「编辑/改库存」同样是灰底圆角：一屏六行、每行三个圆角块，
   人得逐个试才知道哪个能按。状态是状态，不是动作。 */
.state {
  display: flex;
  align-items: center;
  gap: 8rpx;
  flex: none;
}
.state__dot {
  width: 12rpx;
  height: 12rpx;
  border-radius: 9999px;
  background: var(--sh-sub);
}
.state__txt {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.state--ON_SALE .state__dot {
  background: var(--sh-success);
}
.state--ON_SALE .state__txt {
  color: var(--sh-ink);
}
.state--PENDING .state__dot {
  background: var(--sh-warning);
}
.state--REJECTED .state__dot {
  background: var(--sh-danger);
}
.state--REJECTED .state__txt {
  color: var(--sh-danger);
}
.row__ops {
  text-align: end;
}
/* 按钮整宽一行、允许换行：四个按钮在 375 宽下正好排得下，
   五个（将来再加）就换行，而不是把上面那行挤没 */
.row__btns {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 12rpx;
  margin-top: 16rpx;
}
.mini {
  font-size: 24rpx;
  color: var(--sh-sub);
  padding: 8rpx 16rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
</style>
