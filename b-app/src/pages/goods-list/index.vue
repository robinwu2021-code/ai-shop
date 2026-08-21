<script setup lang="ts">
// 商品列表（B-11.3.5 / 3.6）。上下架与改库存是高频操作，做在列表行里，
// 不进详情页 —— 店主蹲在货架前改库存，不该点三层。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onReachBottom, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import type { Category, Goods, GoodsStatus } from "@shared/types";

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

/** 切门店。库存是按店的，切完要重新拉 —— 不重拉会显示上一家店的数 */
function pickStore() {
  const usable = merchant.stores.filter((x) => x.status === "ACTIVE");
  if (usable.length < 2) return;
  uni.showActionSheet({
    itemList: usable.map((x) => x.name || x.storeNo),
    success: ({ tapIndex }) => {
      const target = usable[tapIndex];
      if (!target || target.storeNo === merchant.storeNo) return;
      merchant.switchStore(target.storeNo);
      void load();
    },
  });
}

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
 * 一级类目筛。**类目变必填之后，按类目找货是商家的主路径** ——
 * 一个卖 200 件货的店，没有类目筛就只能靠滚。
 *
 * <p>只给一级：三级树在这条工具栏里放不下，而「食品生鲜 / 日用百货 / 生活服务」
 * 这一层恰好就是商家心里的分堆方式。要更细的用搜索。
 *
 * <p>后端 `GET /biz/goods` 一直支持 `categoryNo`，端点此前写死传 null
 * —— 与已经修过的 `keyword` 是同一种遗漏。
 */
const rootCategories = ref<Category[]>([]);
const categoryNo = ref("");

async function loadCategories() {
  // 取不到不该挡住列表：筛选是锦上添花，商品列表本身要照常出来
  rootCategories.value = await api.mCategoryTree().catch(() => []);
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
   */
  const need = !g.onSale ? gateOf(g) : null;
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

onShow(() => {
  // 类目只取一次：它几乎不变，每次回到列表都重拉是白花的一次往返
  if (!rootCategories.value.length) void loadCategories();
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
      <!-- 建商品/改价属于 biz:goods；店员只有 biz:stock（改库存），不显示这个入口 -->
      <text v-if="merchant.can('biz:goods')" class="add" @tap="edit()">＋ {{ $t("goods.add") }}</text>
    </view>

    <!--
      缺资质汇总。**只在真有的时候出现**，且说清是「当前列表里」的数 ——
      分页只加载了一部分，把它说成全店总数是在编一个自己也不知道的数字。
    -->
    <text v-if="gatedCount" class="gate-sum">
      {{ $t("goods.gateCount", { n: gatedCount }) }}
    </text>

    <!-- 一级类目筛。只有一个类目时不显示 —— 那时它是个恒真的开关 -->
    <scroll-view v-if="rootCategories.length > 1" class="cats" scroll-x>
      <view class="cats__row">
        <text
          v-for="c in rootCategories"
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
    <!--
      搜索。**商品一多，筛选页签解决不了「找某一个」** —— 五个状态页签是分类，
      而商家的真实动作是「涨价的那袋米在哪」。194 条商品时，没有它这一页只能靠滚。
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

    <view v-if="merchant.multiStore" class="store" @tap="pickStore">
      <text class="store__name">{{ merchant.currentStore?.name || "—" }}</text>
      <text class="store__hint">{{ $t("goods.storeStockHint") }}</text>
      <text class="store__switch">{{ $t("goods.switchStore") }}</text>
    </view>

    <sh-empty v-if="empty" :text='$t("goods.empty")'></sh-empty>

    <view v-for="g in list" :key="g.goodsNo" class="sh-card row">
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
      <view class="row__ops">
        <view class="state" :class="'state--' + stateOf(g)">
          <text class="state__dot"></text>
          <text class="state__txt">{{ $t(`goods.status${stateOf(g)}`) }}</text>
        </view>
        <!--
          驳回 / 强制下架的理由。**没有它，商家面对「已驳回」只能猜要改什么** ——
          审计日志只有运营看得到。后端一直在发这个字段，端上此前连声明都没有。
        -->
        <text v-if="g.auditReason" class="reason">{{ g.auditReason }}</text>
        <!--
          缺资质。放在状态那一列而不是标题旁边：它回答的是
          「这件货为什么上不了架」，属于状态，不是商品属性。
        -->
        <text v-if="gateOf(g)" class="reason reason--gate">
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
  margin-top: 8rpx;
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
.store {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 16rpx 24rpx;
  margin-bottom: 16rpx;
  background: var(--sh-faint);
  border-radius: 16rpx;
}
.store__name {
  font-size: 28rpx;
  color: var(--sh-ink);
}
.store__hint {
  flex: 1;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.store__switch {
  font-size: 24rpx;
  color: var(--sh-primary-text);
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
.add {
  font-size: 24rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
  /* 头部是 flex + space-between，被 tabs 挤窄会把「＋ 新建商品」折成两行 —— 锁死不换行、不被压缩 */
  white-space: nowrap;
  flex-shrink: 0;
  margin-left: 16rpx;
}
/* 列表密度对齐 C 端（平台版式约定）：卡片之间只留一条缝。
   商家一天要扫几十次这类列表，行距每多 10rpx，一屏就少一行。 */
.row {
  display: flex;
  gap: 20rpx;
  align-items: center;
  margin-bottom: 14rpx;
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
.row__btns {
  display: flex;
  gap: 16rpx;
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
