<script setup lang="ts">
// 免登录看件页（TDD-收件人物流触达与分享裂变 §3）。
//
// 这是发给**收件人**的页面：从发货短信的短链、或买家分享进来，不需要登录。
// 鉴权靠 URL 里的看件令牌 `t`（后端 HMAC 验签）——令牌即授权。
// 后端返回的是收窄视图：只有物流 + 收货 + 店名 + 商品摘要，没有价格、没有买家身份。
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import { statusTone } from "@shared/strategies/order-view";
import { traceStepKey } from "@shared/strategies/trace-step";
import { api } from "@/api";
import { ROUTES } from "@shared/utils/constants";
import type { TrackView } from "@shared/types";

const { t } = useI18n();
const token = ref("");
const view = ref<TrackView | null>(null);
const loaded = ref(false);

onLoad((q) => {
  token.value = (q?.t as string) ?? "";
  load();
});

async function load() {
  loaded.value = false;
  try {
    view.value = token.value ? await api.track(token.value) : null;
  } catch {
    // 看件是只读页，拉不到就当失效处理，不弹错
    view.value = null;
  } finally {
    loaded.value = true;
  }
  loadMiniLink();
}

// 「在小程序中打开」：仅 H5 端有意义（小程序里已经在小程序内了）。
// 拿到 URL Link 才显示按钮——小程序未发布/未开通时后端返 null，不露点不动的按钮。
const miniLink = ref("");
async function loadMiniLink() {
  // #ifdef H5
  try {
    const r = token.value ? await api.trackMiniLink(token.value) : null;
    miniLink.value = r?.url ?? "";
  } catch {
    miniLink.value = "";
  }
  // #endif
}
function openInMini() {
  // #ifdef H5
  if (miniLink.value) {
    window.location.href = miniLink.value;
  }
  // #endif
}

// 转发：原样带着令牌分享出去 —— 收件人可以再转给家人同住的人代收。
// 一期不做激励，只让链接能传下去（§9）。
onShareAppMessage(() => ({
  title: String(t("track.viewHint")),
  path: token.value ? `${ROUTES.track}?t=${encodeURIComponent(token.value)}` : ROUTES.home,
}));
</script>

<template>
  <sh-scaffold title-key="track.title">
    <!-- 失效：令牌错 / 过期 / 订单没了。只读页，直说 -->
    <sh-empty
      v-if="loaded && !view"
      :text="String($t('track.invalidTitle'))"
      :tip="String($t('track.invalidTip'))"
    ></sh-empty>

    <template v-if="view">
      <!-- 顶部：寄给你的一笔订单 + 发货门店 -->
      <view class="sh-card block">
        <text class="txt-caption sh-muted">{{ $t("track.viewHint") }}</text>
        <text class="txt-strong trk-store">
          {{ view.storeName ? $t("track.shipFrom", { store: view.storeName }) : $t("track.shipFromDefault") }}
        </text>
        <!-- #ifdef H5 -->
        <!-- 拿到 URL Link 才显示：小程序未发布/未开通时不露点不动的按钮 -->
        <view v-if="miniLink" class="sh-btn sh-btn--sm trk-mini" @tap="openInMini">
          {{ $t("track.openInMini") }}
        </view>
        <!-- #endif -->
      </view>

      <!-- 物流状态 + 轨迹 -->
      <view class="sh-card block prog">
        <view class="sh-row sh-row--between">
          <text class="txt-title status" :class="statusTone(view.status)">
            {{ view.trace ? $t(`trace.step.${traceStepKey(view.trace)}`) : $t(`orderStatus.${view.status}`) }}
          </text>
        </view>
        <text v-if="view.expressNo" class="txt-caption sh-muted prog__row sh-num">
          {{ view.expressCompany ? `${view.expressCompany} ${view.expressNo}` : view.expressNo }}
        </text>
        <view v-if="view.trace && view.trace.nodes && view.trace.nodes.length" class="prog__sec">
          <sh-trace :trace="view.trace" :show-map="false"></sh-trace>
        </view>
        <text v-else class="txt-caption sh-muted prog__row">{{ $t("track.noTrace") }}</text>
      </view>

      <!-- 收货信息（掩码号） -->
      <view v-if="view.receiverName || view.receiverAddress" class="sh-card block">
        <text class="txt-caption sh-muted">{{ $t("track.receiverTitle") }}</text>
        <view class="sh-row sh-row--between trk-recv">
          <text class="txt-strong">{{ view.receiverName }}</text>
          <text v-if="view.receiverPhoneMasked" class="txt-caption sh-muted sh-num">{{ view.receiverPhoneMasked }}</text>
        </view>
        <text v-if="view.receiverAddress" class="txt-caption sh-muted">{{ view.receiverAddress }}</text>
      </view>

      <!-- 商品摘要：图 + 名 + 规格 + 件数，不含价格 -->
      <view v-if="view.items && view.items.length" class="sh-card block">
        <view class="sh-row sh-row--between">
          <text class="txt-caption sh-muted">{{ $t("track.itemsTitle") }}</text>
          <text class="txt-caption sh-muted">{{ $t("track.itemCount", { n: view.items.reduce((s, i) => s + i.qty, 0) }) }}</text>
        </view>
        <view v-for="(it, i) in view.items" :key="i" class="sh-row trk-item">
          <image v-if="it.cover" class="trk-item__img" :src="it.cover" mode="aspectFill"></image>
          <view class="sh-fill">
            <text class="txt-sub trk-item__title">{{ it.title }}</text>
            <text v-if="it.spec" class="txt-caption sh-muted">{{ it.spec }}</text>
          </view>
          <text class="txt-caption sh-muted sh-num">×{{ it.qty }}</text>
        </view>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.block { margin-bottom: 16rpx; }
.trk-store { display: block; margin-top: 8rpx; }
.trk-mini { margin-top: 16rpx; }
.status { font-weight: 600; }
.prog__row { display: block; margin-top: 8rpx; }
.prog__sec { margin-top: 16rpx; }
.trk-recv { margin-top: 8rpx; }
.trk-item { gap: 16rpx; align-items: center; padding: 12rpx 0; }
.trk-item__img { width: 96rpx; height: 96rpx; border-radius: 12rpx; flex: none; }
.trk-item__title { display: block; }
</style>
