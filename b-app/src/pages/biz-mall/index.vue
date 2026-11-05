<template>
  <sh-scaffold title-key="bizMall.store.title" :denied="!merchant.can('biz:store:admin')">
    <biz-store-tag readonly></biz-store-tag>
    <view class="sh-card">
      <view class="sh-row sh-row--between">
        <text class="txt-caption txt-quiet">{{ t("bizMall.store.hint") }}</text>
        <text class="sh-link" @tap="goOrders">{{ t("bizMall.orders.title") }}</text>
      </view>
    </view>

    <sh-empty v-if="!items.length" :pending="!loaded" :failed="failed" @retry="load"
      :text="String(t('bizMall.store.empty'))"></sh-empty>
    <text v-if="loaded && front && front.canProcure === false" class="sh-hint is-danger">{{ t("bizMall.store.cannotProcure") }}</text>

    <view v-for="i in items" :key="i.versionNo" class="sh-card" @tap="buy(i)">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ i.name }}</text>
        <text class="txt-strong sh-num">{{ mallMoney(i.price) }}</text>
      </view>
      <text v-if="i.title && i.title !== i.name" class="txt-sub">{{ i.title }}</text>
      <text v-if="i.description" class="txt-caption txt-quiet">{{ i.description }}</text>
      <view class="sh-row sh-mt-xs chips">
        <text v-for="(k, n) in i.kinds" :key="n" class="sh-chip">{{ k }}</text>
      </view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 平台商城（商家向平台采购）：列出本店经营类型能买的套餐、端包、硬件与组合套装。
// 首期只走线下银行转账，点进去即报价下单；能不能付款以报价结果为准 ——
// 列表上的 readiness 是在线支付的口径，线下转账另有一套判断，这里不拿它挡人。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { mallMoney } from "@/shared/mall";
import type { MallMoney, MallStorefront } from "@/api/mall";

const { t } = useI18n();
const merchant = useMerchantStore();
const front = ref<MallStorefront | null>(null);
const loaded = ref(false);
const failed = ref(false);

interface Item {
  versionNo: string;
  name: string;
  title?: string;
  description?: string | null;
  price?: MallMoney;
  kinds: string[];
}

const items = computed<Item[]>(() =>
  (front.value?.products ?? []).flatMap((p) =>
    (p.skus ?? [])
      .filter((s) => s.sku?.skuVersionNo)
      .map((s) => ({
        versionNo: s.sku!.skuVersionNo!,
        name: p.product?.name ?? s.sku!.title ?? "",
        title: s.sku!.title,
        description: p.product?.description,
        price: s.sku!.price,
        kinds: [...new Set((s.sku!.components ?? []).map((c) => String(t(`bizMall.kind.${c.kind}`))))],
      })),
  ),
);

async function load() {
  failed.value = false;
  const store = merchant.storeNo;
  try {
    const r = await api.mMallStorefront(store);
    if (store !== merchant.storeNo) return;
    front.value = r;
  } catch {
    failed.value = true;
  } finally {
    loaded.value = true;
  }
}

function buy(i: Item) {
  const q = `sku=${encodeURIComponent(i.versionNo)}&currency=${encodeURIComponent(front.value?.currency ?? "")}`
    + `&name=${encodeURIComponent(i.name)}`;
  uni.navigateTo({ url: `${ROUTES.bizMallCheckout}?${q}` });
}

function goOrders() {
  uni.navigateTo({ url: ROUTES.bizMallOrders });
}

onShow(load);
</script>

<style scoped>
.chips {
  gap: 8rpx;
  flex-wrap: wrap;
}
</style>
