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

const tab = ref<GoodsStatus | "">("");
const list = ref<Goods[]>([]);
const loading = ref(false);

const empty = computed(() => !loading.value && !list.value.length);

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
    await api.mSaveStock(g.goodsNo, sku.skuNo, Math.floor(n));
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
  <sh-scaffold title-key="goods.title" tab="goods">
    <view class="bar">
      <sh-tabs
        :items="TABS.map((t) => ({ key: t.key, label: String($t(t.labelKey)) }))"
        :active="tab"
        @change="switchTab"
      ></sh-tabs>
      <text class="add" @tap="edit()">＋ {{ $t("goods.add") }}</text>
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
        <text class="sh-chip" :class="g.onSale ? 'sh-chip--primary' : ''">
          {{ g.onSale ? $t("goods.statusON_SALE") : $t("goods.statusOFF_SALE") }}
        </text>
        <view class="row__btns">
          <text class="mini" @tap="edit(g)">{{ $t("goods.edit") }}</text>
          <text class="mini" @tap="toggle(g)">
            {{ g.onSale ? $t("goods.offSale") : $t("goods.onSale") }}
          </text>
          <text class="mini" @tap="editStock(g)">{{ $t("goods.editStock") }}</text>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
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
