<script setup lang="ts">
// 商品列表（B-11.3.5 / 3.6）。上下架与改库存是高频操作，做在列表行里，
// 不进详情页 —— 店主蹲在货架前改库存，不该点三层。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import type { Goods, GoodsStatus } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const TABS: { key: GoodsStatus | ""; labelKey: string }[] = [
  { key: "", labelKey: "common.all" },
  { key: "ON_SALE", labelKey: "goods.statusON_SALE" },
  { key: "OFF_SALE", labelKey: "goods.statusOFF_SALE" },
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
const loading = ref(false);

const empty = computed(() => !loading.value && !list.value.length);

/**
 * 这一行到底是什么状态。
 *
 * **不能只看 `onSale`**：新建和每次改动都会回到审核中，那时 `onSale` 是 false，
 * 照布尔值渲染就成了「已下架」+ 一个必然失败的「上架」。
 * 后端下发的 `status` 才是四态（AUDITING / REJECTED / ON_SALE / OFF_SALE）；
 * 老数据没有这个字段时回落布尔值。
 */
function stateOf(g: Goods) {
  return g.status ?? (g.onSale ? "ON_SALE" : "OFF_SALE");
}

/** 审核中或被驳回 —— 这两种状态下商家自己按不了上架 */
function pending(g: Goods) {
  const s = stateOf(g);
  return s === "AUDITING" || s === "REJECTED";
}

async function load() {
  if (!merchant.isActive) return;
  loading.value = true;
  try {
    const res = await api.mGoodsList({ status: tab.value || undefined, size: 50 });
    list.value = res.records;
  } finally {
    loading.value = false;
  }
}

function switchTab(key: GoodsStatus | "") {
  tab.value = key;
  void load();
}

async function toggle(g: Goods) {
  await api.mToggleGoods(g.goodsNo, !g.onSale);
  await load();
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

function edit(g?: Goods) {
  uni.navigateTo({ url: g ? `${ROUTES.goodsEdit}?goodsNo=${g.goodsNo}` : ROUTES.goodsEdit });
}

/** 总库存 = 各规格之和。单规格可就地改（editStock），多规格进编辑页逐个改 */
function stockOf(g: Goods) {
  return g.skus.reduce((s, k) => s + k.stock, 0);
}

onShow(load);
</script>

<template>
  <!--
    列表本身要 `biz:stock`（`/biz/goods`）。**它是 tabBar 四页之一**，
    客服与配送员没有这个码 —— 不判的话他们每天点一次「商品」，每天吃一个 70006 toast，
    而页内那几个按钮反倒早就按 can() 裁好了。门禁漏的偏偏是列表这一件必做的事。
  -->
  <sh-scaffold title-key="goods.title" tab="goods" :denied="!merchant.can('biz:stock')">
    <view class="bar">
      <sh-tabs
        :items="TABS.map((t) => ({ key: t.key, label: String($t(t.labelKey)) }))"
        :active="tab"
        @change="switchTab"
      ></sh-tabs>
      <!-- 建商品/改价属于 biz:goods；店员只有 biz:stock（改库存），不显示这个入口 -->
      <text v-if="merchant.can('biz:goods')" class="add" @tap="edit()">＋ {{ $t("goods.add") }}</text>
    </view>

    <!--
      当前门店。**多店才显示** —— 单店商家看到「当前门店」只会疑惑还有别的店。
      不显示的代价是实测出来的：商家给某家店设了 1 件库存，商品页却显示主体总量 91，
      他会以为还有货。

      提示里那半句「没单独设过的门店按 0 卖」是后端的真实语义
      （`StockPortImpl.hasStoreStock`：任意一家店设过，这个 SKU 就整体转成按店算，
      没设的店按 0 —— 少卖可恢复，超卖不可）。真实链路上验过：
      在新店设了 5 件，主店那 80 件当场变成 0 —— **不写出来的话没人能预料到**。
    -->
    <view v-if="merchant.multiStore" class="store" @tap="pickStore">
      <text class="store__name">{{ merchant.currentStore?.name || "—" }}</text>
      <text class="store__hint">{{ $t("goods.storeStockHint") }}</text>
      <text class="store__switch">{{ $t("goods.switchStore") }}</text>
    </view>

    <sh-empty v-if="empty" :text='$t("goods.empty")'></sh-empty>

    <view v-for="g in list" :key="g.goodsNo" class="sh-card row">
      <text class="row__cover">{{ g.cover }}</text>
      <view class="row__main">
        <text class="row__title">{{ g.title }}</text>
        <view class="row__meta">
          <text class="row__price sh-num">{{ money(g.price) }}</text>
          <text class="sh-muted">{{ $t("goods.stock") }} {{ stockOf(g) }}</text>
        </view>
      </view>
      <view class="row__ops">
        <text class="sh-chip" :class="{ 'sh-chip--primary': g.onSale, 'is-warn': pending(g) }">
          {{ $t(`goods.status${stateOf(g)}`) }}
        </text>
        <view class="row__btns">
          <!-- 编辑与上下架都会改价/改可见性 → biz:goods；改库存只是数量 → biz:stock。
               这条缝就是店员的权限边界：卖完了能马上改数，但改不了价 -->
          <text v-if="merchant.can('biz:goods')" class="mini" @tap="edit(g)">
            {{ $t("goods.edit") }}
          </text>
          <!-- 审核中/已驳回时**不给上架按钮**：后端必拒（70003），
               留着它等于给商家一个永远点不动的按钮，而错在哪一句话都没有 -->
          <text v-if="merchant.can('biz:goods') && !pending(g)" class="mini" @tap="toggle(g)">
            {{ g.onSale ? $t("goods.offSale") : $t("goods.onSale") }}
          </text>
          <text v-if="merchant.can('biz:stock')" class="mini" @tap="editStock(g)">
            {{ $t("goods.editStock") }}
          </text>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
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
  font-size: 26rpx;
  color: var(--sh-ink);
}
.store__hint {
  flex: 1;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.store__switch {
  font-size: 24rpx;
  color: var(--sh-primary);
}
.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24rpx;
}
.add {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-primary);
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
.row__title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.row__meta {
  display: flex;
  gap: 20rpx;
  align-items: baseline;
  margin-top: 8rpx;
}
.row__price {
  font-size: 30rpx;
  font-weight: 700;
  color: var(--sh-primary);
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
