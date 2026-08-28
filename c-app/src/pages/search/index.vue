<script setup lang="ts">
// 搜索：商品 + 商家两个结果域。
// 两者分 tab 而不是混排 —— 用户搜「理发」既可能想找服务商品，也可能想找那家店，
// 混排会让两类结果互相挤掉，分开各自完整展示更好用。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCartStore } from "@/stores/cart";
import { GOODS_COVER_FALLBACK, ROUTES, STORAGE } from "@shared/utils/constants";
import { firstSku } from "@shared/utils/goods";
import { flyToCart, tapPoint } from "@/shared/fly";
import type { Goods, Merchant } from "@shared/types";

const cart = useCartStore();

const keyword = ref("");
const tab = ref<"goods" | "merchants">("goods");
const goods = ref<Goods[]>([]);
const merchants = ref<Merchant[]>([]);
const searched = ref(false);
const history = ref<string[]>([]);

const HISTORY_MAX = 10;

const empty = computed(
  () => searched.value && !goods.value.length && !merchants.value.length,
);

function loadHistory() {
  history.value = (uni.getStorageSync(STORAGE.searchHistory) as string[]) || [];
}

function pushHistory(k: string) {
  const next = [k, ...history.value.filter((x) => x !== k)].slice(
    0,
    HISTORY_MAX,
  );
  history.value = next;
  uni.setStorageSync(STORAGE.searchHistory, next);
}

function clearHistory() {
  history.value = [];
  uni.removeStorageSync(STORAGE.searchHistory);
}

async function search(k = keyword.value) {
  const q = k.trim();
  if (!q) return;
  keyword.value = q;
  pushHistory(q);
  // 两个域并行查，切 tab 时不用再等
  const [g, m] = await Promise.all([
    api.goodsList({ keyword: q, size: 50 }),
    api.merchantList({ keyword: q }),
  ]);
  goods.value = g.records;
  merchants.value = m;
  searched.value = true;
  // 哪边有结果就默认停在哪边，避免用户搜到了却看见一个空 tab
  if (!g.records.length && m.length) tab.value = "merchants";
  else tab.value = "goods";
}

function openGoods(g: Goods) {
  uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${g.goodsNo}` });
}

function openMerchant(m: Merchant) {
  uni.navigateTo({ url: `${ROUTES.merchant}?merchantNo=${m.merchantNo}` });
}

async function add(g: Goods, e: unknown) {
  try {
    await cart.add(g.goodsNo, firstSku(g).skuNo, 1);
    const p = tapPoint(e as Parameters<typeof tapPoint>[0]);
    flyToCart(p.x, p.y, g.cover || GOODS_COVER_FALLBACK);
  } catch (err) {
    uni.showToast({ title: (err as Error).message, icon: "none" });
  }
}

onLoad((q) => {
  loadHistory();
  const k = (q?.keyword as string) || "";
  if (k) search(decodeURIComponent(k));
});
</script>

<template>
  <sh-scaffold title-key="search.title">
    <!-- 搜索框 -->
    <view class="searchbar">
      <input
        maxlength="32"
        v-model="keyword"
        class="txt-body searchbar__input sh-fill"
        :placeholder="$t('search.placeholder')"
        confirm-type="search"
        focus
        @confirm="search()"
      />
      <view class="txt-strong searchbar__btn" @tap="search()">{{ $t("search.go") }}</view>
    </view>

    <!-- 搜索历史 -->
    <view v-if="!searched && history.length" class="sh-block">
      <view class="sh-block__head hist__head">
        <text class="sh-muted">{{ $t("search.history") }}</text>
        <text class="txt-caption hist__clear" @tap="clearHistory">{{
          $t("search.clear")
        }}</text>
      </view>
      <view class="hist__list">
        <text
          v-for="h in history"
          :key="h"
          class="txt-caption sh-chip hist__item"
          @tap="search(h)"
        >
          {{ h }}
        </text>
      </view>
    </view>

    <!-- 结果 -->
    <view v-if="searched" class="sh-block">
      <view class="sh-block__head sh-block__head--tabs">
        <sh-tabs
          :items="[
            {
              key: 'goods',
              label: String($t('search.goodsTab', { n: goods.length })),
            },
            {
              key: 'merchants',
              label: String($t('search.merchantTab', { n: merchants.length })),
            },
          ]"
          :active="tab"
          @change="(k: string) => (tab = k as typeof tab)"
        ></sh-tabs>
      </view>

      <template v-if="tab === 'goods'">
        <biz-goods-card
          v-for="g in goods"
          :key="g.goodsNo"
          :goods="g"
          @add="add(g, $event)"
          @tap="openGoods(g)"
        ></biz-goods-card>
        <sh-empty
          bare
          v-if="!goods.length"
          :text="$t('search.noGoods')"
        ></sh-empty>
      </template>

      <template v-else>
        <view
          v-for="m in merchants"
          :key="m.merchantNo"
          class="mcard"
          @tap="openMerchant(m)"
        >
          <biz-merchant-bar
            :merchant="m"
            @tap="openMerchant(m)"
          ></biz-merchant-bar>
          <text class="txt-caption mcard__desc">{{ m.desc }}</text>
          <view class="mcard__meta">
            <text class="sh-chip">{{ $t(`merchant.type.${m.type}`) }}</text>
            <text class="sh-chip sh-num">
              {{ $t("merchant.goodsTab", { n: m.goodsCount }) }}
            </text>
            <text class="sh-chip sh-num">{{
              $t("search.orders", { n: m.salesCount })
            }}</text>
          </view>
        </view>
        <sh-empty
          bare
          v-if="!merchants.length"
          :text="$t('search.noMerchant')"
        ></sh-empty>
      </template>

      <sh-empty
        bare
        v-if="empty"
        :text="$t('search.nothing', { k: keyword })"
      ></sh-empty>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.searchbar {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.searchbar__input {
  background: var(--sh-surface);
  border-radius: 9999px;
  padding: 24rpx 32rpx;
}
.searchbar__btn {
  flex: 0 0 auto;
  padding: 24rpx 40rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
}
.block {
  margin-top: 24rpx;
}
/* 布局由 .sh-block__head 给，这里只补「清空」右对齐 */
.hist__head {
  align-items: center;
  justify-content: space-between;
}
.hist__clear {
  color: var(--sh-primary-text);
}
.hist__list {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  /* 块本身只管上下留白，横向由内容自己给 */
  padding: 0 26rpx;
  margin-top: 20rpx;
}
.hist__item {
  padding: 12rpx 26rpx;
}
/* 商家结果在结果块内成行 —— 行与行之间靠内边距分隔，不再各自一张卡 */
.mcard {
  padding: 20rpx 26rpx;
}
.mcard__desc {
  display: block;
  margin-top: 20rpx;
}
.mcard__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 20rpx;
}
</style>
