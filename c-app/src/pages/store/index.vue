<script setup lang="ts">
// 门店主页（C-ST-01~10）。**一期主获客路径**：商家把店铺码印在包装袋、发进自己的
// 客户群，老客扫码直达这里（ADR-004 决策 3）。
//
// 关键：**这是交易页，不是店铺介绍页**。
// 粮油副食不是「逛」出来的，复购路径必须压到三步 —— 打开 → 常买 → 下单。
// 所以登录用户第一屏是「我买过的」，店招和简介往后放；未登录才退化成店铺热销。
//
// 另一条：**不经过首页与选社区**。老客扫码是来买东西的，中间插一个「请先选择你的社区」
// 会把人挡在门外 —— 游客可逛，加购时再引导登录。
import { computed, ref } from "vue";
import { onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useCartStore } from "@/stores/cart";
import { useUserStore } from "@/stores/user";
import { ROUTES } from "@shared/utils/constants";
import { money } from "@shared/utils/money";
import { buildShareMessage } from "@shared/ports/share";
import type { FrequentItem, StoreHome } from "@shared/types";

const { t } = useI18n();
const cart = useCartStore();
const user = useUserStore();

const merchantNo = ref("");
const data = ref<StoreHome | null>(null);
const frequent = ref<FrequentItem[]>([]);
const keyword = ref("");
const busy = ref(false);

/** 店内搜索：只在本店范围内找，与全平台搜索分开（C-ST-06） */
const goods = computed(() => {
  const list = data.value?.goods ?? [];
  const k = keyword.value.trim().toLowerCase();
  if (!k) return list;
  return list.filter(
    (g) => g.title.toLowerCase().includes(k) || g.subtitle.toLowerCase().includes(k),
  );
});

const hasFrequent = computed(() => frequent.value.some((f) => f.times > 0));

async function load() {
  if (!merchantNo.value) return;
  const [home, freq] = await Promise.all([
    api.storeHome(merchantNo.value, fromParam.value),
    api.frequentItems(merchantNo.value),
  ]);
  data.value = home;
  frequent.value = freq;
}

const fromParam = ref("");

onLoad(async (q) => {
  merchantNo.value = (q?.merchantNo as string) || "";
  // from=QR 表示扫码进店 —— 归因写在服务端，决定订单的 trafficSource 与商家费率档
  fromParam.value = (q?.from as string) || "";
  await load();
});

async function addOne(f: FrequentItem) {
  if (f.invalid) {
    uni.showToast({ title: t("store.itemInvalid"), icon: "none" });
    return;
  }
  try {
    await cart.add(f.goodsNo, f.skuNo, 1);
    uni.showToast({ title: t("common.added"), icon: "none" });
  } catch (e) {
    // 加购会被业务规则拒绝（生鲜过了当日截单、限购、超区…）。
    // 不接住的话点了没反应，用户只会以为按钮坏了
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 一键再来一单：拿最近一笔本店订单整单复制（C-ST-03） */
async function reorder() {
  if (busy.value) return;
  if (!user.isLogin) {
    uni.navigateTo({ url: ROUTES.login });
    return;
  }
  busy.value = true;
  try {
    const res = await api.orderList({ size: 20 });
    const last = res.records.find(
      (o) => o.status !== "CANCELLED" && o.items.some((it) => !it.isGift),
    );
    if (!last) {
      uni.showToast({ title: t("store.noHistory"), icon: "none" });
      return;
    }
    const r = await api.reorderFrom(last.orderNo);
    await cart.load();
    // 丢了什么、涨了什么都要说清楚 —— 静默少加是投诉源头
    const parts = [t("store.reorderAdded", { n: r.added })];
    if (r.dropped.length) parts.push(t("store.reorderDropped", { s: r.dropped.join("、") }));
    if (r.priceUp.length) parts.push(t("store.reorderPriceUp", { s: r.priceUp.join("、") }));
    uni.showModal({ title: t("store.reorder"), content: parts.join("\n"), showCancel: false });
  } finally {
    busy.value = false;
  }
}

async function toggleFav() {
  if (!user.isLogin) {
    uni.navigateTo({ url: ROUTES.login });
    return;
  }
  const on = await api.toggleFavoriteStore(merchantNo.value);
  if (data.value) data.value.favorited = on;
  uni.showToast({ title: on ? t("store.faved") : t("store.unfaved"), icon: "none" });
}

function gotoGoods(goodsNo: string) {
  uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${goodsNo}` });
}

// 分享出去的链接必须带 merchantNo，否则进店归因断掉（ADR-004 §5.4）
onShareAppMessage(() =>
  buildShareMessage({
    title: data.value ? `${data.value.merchant.name} · ${data.value.store.announcement}` : "",
    // from=SHARE 让落地页知道这是分享进来的，与扫码同样计入商家自带客流
    path: `${ROUTES.store}?from=SHARE`,
    merchantNo: merchantNo.value,
  }),
);
</script>

<template>
  <sh-scaffold v-if="data" :padded="true">
    <!-- 店招：登录用户看到的是「常买」优先，这里只占一行 -->
    <view class="store">
      <text class="store__logo">{{ data.merchant.logo }}</text>
      <view class="store__main">
        <view class="store__row">
          <text class="store__name">{{ data.merchant.name }}</text>
          <text v-if="data.merchant.verified" class="sh-chip sh-chip--primary">
            {{ $t("merchant.verified") }}
          </text>
        </view>
        <text class="sh-muted">
          {{ data.store.openHours }} · {{ data.store.address }}
        </text>
      </view>
      <text class="fav" :class="{ 'is-on': data.favorited }" @tap="toggleFav">
        {{ data.favorited ? "★" : "☆" }}
      </text>
    </view>

    <!-- 店铺公告：店主自发，老客一进来就看到 -->
    <view v-if="data.store.announcement" class="notice">
      <text>{{ data.store.announcement }}</text>
    </view>

    <!-- 第一屏：我买过的。这是本页存在的理由 -->
    <view class="sec">
      <text class="sh-h2">{{ hasFrequent ? $t("store.frequent") : $t("store.hot") }}</text>
      <text v-if="hasFrequent" class="link" @tap="reorder">{{ $t("store.reorder") }}</text>
    </view>

    <view v-for="f in frequent" :key="f.skuNo" class="freq" :class="{ 'is-off': f.invalid }">
      <text class="freq__cover">{{ f.cover }}</text>
      <view class="freq__main" @tap="gotoGoods(f.goodsNo)">
        <text class="freq__title">{{ f.title }}</text>
        <text class="sh-muted">{{ f.spec }}</text>
        <view class="freq__tags">
          <text v-if="f.times > 1" class="sh-chip">{{ $t("store.times", { n: f.times }) }}</text>
          <text v-if="f.price > f.lastPrice" class="sh-chip sh-chip--warning">
            {{ $t("store.priceUp", { p: money(f.lastPrice) }) }}
          </text>
          <text v-if="f.invalid" class="sh-chip sh-chip--danger">{{ $t("store.invalid") }}</text>
        </view>
      </view>
      <view class="freq__buy">
        <text class="freq__price sh-num">{{ money(f.price) }}</text>
        <text class="add" @tap="addOne(f)">＋</text>
      </view>
    </view>

    <!-- 履约说明：超区在店铺页就说清楚，不等到结算 -->
    <view class="ship">
      <text class="sh-muted">{{ $t("store.fulfillHint") }}</text>
    </view>

    <!-- 店内搜索 + 全部商品 -->
    <view class="sec">
      <text class="sh-h2">{{ $t("store.allGoods") }}</text>
      <text class="sh-muted sh-num">{{ goods.length }}</text>
    </view>
    <input v-model="keyword" class="search" :placeholder="$t('store.searchPh')" />

    <view class="grid">
      <biz-goods-card
        v-for="g in goods"
        :key="g.goodsNo"
        :goods="g"
        @tap="gotoGoods(g.goodsNo)"
      ></biz-goods-card>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.store {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 8rpx 0 24rpx;
}
.store__logo {
  width: 96rpx;
  height: 96rpx;
  border-radius: 28rpx;
  background: var(--sh-surface);
  font-size: 56rpx;
  text-align: center;
  line-height: 96rpx;
}
.store__main {
  flex: 1;
  min-width: 0;
}
.store__row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.store__name {
  font-size: 34rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.fav {
  font-size: 44rpx;
  color: var(--sh-sub);
}
.fav.is-on {
  color: var(--sh-warning);
}
.notice {
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  background: var(--sh-primary-tint);
  color: var(--sh-primary);
  font-size: 26rpx;
  line-height: 1.6;
}
.sec {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 36rpx 0 16rpx;
}
.link {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-primary);
}
.freq {
  display: flex;
  align-items: center;
  gap: 20rpx;
  background: var(--sh-surface);
  border-radius: 28rpx;
  padding: 20rpx 24rpx;
  margin-bottom: 16rpx;
}
.freq.is-off {
  opacity: 0.5;
}
.freq__cover {
  width: 88rpx;
  height: 88rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 52rpx;
  text-align: center;
  line-height: 88rpx;
}
.freq__main {
  flex: 1;
  min-width: 0;
}
.freq__title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.freq__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx;
  margin-top: 10rpx;
}
.freq__buy {
  text-align: end;
}
.freq__price {
  display: block;
  font-size: 30rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.add {
  display: inline-block;
  margin-top: 10rpx;
  width: 56rpx;
  height: 56rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 32rpx;
  text-align: center;
  line-height: 56rpx;
}
.ship {
  margin-top: 24rpx;
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  line-height: 1.6;
}
.search {
  height: 80rpx;
  padding: 0 24rpx;
  border-radius: 24rpx;
  background: var(--sh-surface);
  font-size: 26rpx;
  color: var(--sh-ink);
  margin-bottom: 20rpx;
}
.grid {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
}
</style>
