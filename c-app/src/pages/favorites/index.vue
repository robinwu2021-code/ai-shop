<script setup lang="ts">
import BizLoginPanel from "@/components/biz/biz-login-panel.vue";
/*
 * 我的收藏（原型 g08 · TDD-C端商品收藏与送达判断）。
 *
 * 两栏：商品 / 店铺。商品按收藏时间倒序；**下架的压淡但不自动删** ——
 * 他收藏它是有原因的，自动消失会让人以为数据丢了，由他自己点「删除」。
 * 店铺只列收藏的，不混入「常去的店」（那是归因推出来的，不是他点的）。
 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { onReachBottom, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCartStore } from "@/stores/cart";
import { useUserStore } from "@/stores/user";
import { GOODS_COVER_FALLBACK, ROUTES } from "@shared/utils/constants";
import { goodsUrl } from "@/shared/goods-route";
import { firstBuyableSku } from "@shared/utils/goods";
import { flyToCart, tapPoint } from "@/shared/fly";
import type { Goods, FavoriteStoreView } from "@shared/types";

const { t } = useI18n();
const cart = useCartStore();
const user = useUserStore();

const PAGE_SIZE = 20;
const tab = ref<"goods" | "store">("goods");
const goods = ref<Goods[]>([]);
const goodsTotal = ref(0);
const stores = ref<FavoriteStoreView[]>([]);
const page = ref(1);
const loaded = ref(false);
const failed = ref(false);

const tabs = computed(() => [
  { key: "goods", label: String(t("favorites.goodsTab", { n: goodsTotal.value })) },
  { key: "store", label: String(t("favorites.storeTab", { n: stores.value.length })) },
]);

async function load() {
  // 统一闸：没手机号弹授权层，绑完自动把这次加载补上
  if (user.isPhoneLogin) await doLoad();
}

async function doLoad() {
  const token = user.token;
  try {
    const [g, s] = await Promise.all([api.favoriteGoods(1, PAGE_SIZE), api.favoriteStores()]);
    if (user.token !== token || !user.isPhoneLogin) return;
    goods.value = g.records;
    goodsTotal.value = g.total;
    page.value = 1;
    stores.value = s;
    failed.value = false;
  } catch {
    if (user.token !== token || !user.isPhoneLogin) return;
    failed.value = true;
  }
  loaded.value = true;
}

async function more() {
  const token = user.token;
  if (tab.value !== "goods" || goods.value.length >= goodsTotal.value) return;
  const next = await api.favoriteGoods(page.value + 1, PAGE_SIZE).catch(() => null);
  if (!next || user.token !== token || !user.isPhoneLogin) return;
  page.value += 1;
  goods.value = [...goods.value, ...next.records];
}

/** 已下架：压淡显示，给「删除」—— 在售但卖完了的不算（商品卡自己会说「已售罄」） */
function dead(g: Goods): boolean {
  return !g.onSale;
}

async function remove(g: Goods) {
  try {
    const { favorited } = await api.toggleFavoriteGoods(g.goodsNo);
    if (!favorited) {
      goods.value = goods.value.filter((x) => x.goodsNo !== g.goodsNo);
      goodsTotal.value = Math.max(0, goodsTotal.value - 1);
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

async function addToCart(g: Goods, e: unknown) {
  try {
    await cart.add(g.goodsNo, firstBuyableSku(g).skuNo, 1, g.store?.storeNo);
    const p = tapPoint(e as Parameters<typeof tapPoint>[0]);
    flyToCart(p.x, p.y, g.cover || GOODS_COVER_FALLBACK);
  } catch (err) {
    uni.showToast({ title: (err as Error).message, icon: "none" });
  }
}

function openGoods(g: Goods) {
  if (dead(g)) return;
  uni.navigateTo({ url: goodsUrl(g) });
}

function openStore(m: FavoriteStoreView) {
  uni.navigateTo({ url: `${ROUTES.store}?no=${m.storeNo || m.merchantNo}` });
}

onShow(load);
onReachBottom(more);
// Covers both a native phone login and a known phone account restored silently after first onShow.
watch(() => user.isPhoneLogin ? user.token : "", () => {
  goods.value = []; goodsTotal.value = 0; stores.value = []; page.value = 1;
  loaded.value = false; failed.value = false;
  void load();
});
</script>

<template>
  <sh-scaffold title-key="favorites.title">
    <biz-login-panel v-if="!user.isPhoneLogin" />
    <template v-if="user.isPhoneLogin">
    <sh-tabs :items="tabs" :active="tab" @change="(k: string) => (tab = k as typeof tab)"></sh-tabs>

    <view v-if="tab === 'goods'" class="sh-block">
      <view v-for="g in goods" :key="g.goodsNo" class="item">
        <biz-goods-card :goods="g" :class="{ 'is-dead': dead(g) }" @add="addToCart(g, $event)" @tap="openGoods(g)"></biz-goods-card>
        <view v-if="dead(g)" class="item__dead sh-row">
          <text class="txt-caption txt-quiet sh-fill">{{ $t("favorites.offShelf") }}</text>
          <text class="sh-link" @tap="remove(g)">{{ $t("favorites.remove") }}</text>
        </view>
      </view>
      <sh-empty
        bare
        v-if="!goods.length"
        :pending="!loaded"
        :failed="failed"
        @retry="load"
        :text="$t('favorites.emptyGoods')"
        :tip="$t('favorites.emptyGoodsTip')"
      ></sh-empty>
    </view>

    <view v-else class="sh-block">
      <biz-shop-row v-for="m in stores" :key="m.storeNo || m.merchantNo" :merchant="m" @tap="openStore(m)"></biz-shop-row>
      <sh-empty
        bare
        v-if="!stores.length"
        :pending="!loaded"
        :failed="failed"
        @retry="load"
        :text="$t('favorites.emptyStores')"
      ></sh-empty>
    </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
/* 只弱化商品展示；下架原因与移除操作保持原有对比度。 */
.is-dead {
  opacity: 0.55;
}
.item__dead {
  gap: 16rpx;
  padding: 0 24rpx 20rpx;
}
</style>
