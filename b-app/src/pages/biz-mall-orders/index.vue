<template>
  <sh-scaffold title-key="bizMall.orders.title" :denied="!merchant.can('biz:store:admin')">
    <biz-store-tag readonly></biz-store-tag>
    <sh-empty v-if="!rows.length" :pending="!loaded" :failed="failed" @retry="load"
      :text="String(t('bizMall.orders.empty'))"></sh-empty>
    <view v-for="r in rows" :key="r.order?.orderNo" class="sh-card" @tap="open(r.order?.orderNo)">
      <view class="sh-row sh-row--between">
        <text class="txt-caption sh-num">{{ r.order?.orderNo }}</text>
        <text class="txt-strong sh-num">{{ mallMoney(r.order?.payable) }}</text>
      </view>
      <view class="sh-row sh-row--between sh-mt-xs">
        <text class="txt-caption txt-quiet">{{ mallTime(r.order?.createdAt) }}</text>
        <text class="sh-chip" :class="tone(r.paymentStatus)">{{ t(`bizMall.payment.${r.paymentStatus ?? "UNPAID"}`) }}</text>
      </view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 本店的商城订单（向平台采购的单，不是顾客的销售单）。付款状态以后端资金维度为准：
// 商家申报了转账也仍是「待付款」，运营核到款才变「已付款」。
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { mallMoney, mallTime } from "@/shared/mall";
import type { MallOrderSummary } from "@/api/mall";

const { t } = useI18n();
const merchant = useMerchantStore();
const rows = ref<MallOrderSummary[]>([]);
const loaded = ref(false);
const failed = ref(false);

function tone(s?: string) {
  return s === "PAID" ? "sh-chip--primary" : s === "CLOSED" ? "" : "sh-chip--warning";
}

async function load() {
  failed.value = false;
  const store = merchant.storeNo;
  try {
    const r = await api.mMallOrders(store);
    if (store !== merchant.storeNo) return;
    rows.value = r;
  } catch {
    failed.value = true;
  } finally {
    loaded.value = true;
  }
}

function open(orderNo?: string) {
  if (orderNo) uni.navigateTo({ url: `${ROUTES.bizMallOrder}?orderNo=${encodeURIComponent(orderNo)}` });
}

onShow(load);
</script>
