<script setup lang="ts">
/*
 * 分享入口（TDD-C 端裂变与商家招募 §3.2）。商品页、门店页、商家页共用这一颗。
 *
 * **此前只有商品页有一颗**，而且挂在 `canNativeShare()` 上 —— 它只在微信小程序为 true，
 * 于是 H5（`/c/`）上三个页面一个分享入口都没有，而 H5 没有胶囊菜单可以兜底。
 * 门店页与商家页则连小程序上都只能从右上角「···」转发 —— 那个位置没人会去找。
 *
 * 两端行为不同，但**入口始终在**：
 * · 小程序：一颗 `open-type="share"` 的原生按钮盖在上面，走微信转发；
 * · H5：复制带归因参数的链接。
 *
 * 归因参数由调用方给（`inviterNo` / `merchantNo`），这里只负责把它拼进链接 ——
 * 少了它，分享带来的人算不到任何人头上。
 */
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { canNativeShare, withAttribution } from "@shared/ports/share";

const props = defineProps<{
  /** 分享落地的页面路径，如 `/pages/goods/index?goodsNo=G1` */
  path: string;
  inviterNo?: string;
  merchantNo?: string;
  /** 紧凑模式：只画图标不画文字（商品页标题行那种并排的位置） */
  compact?: boolean;
}>();

const { t } = useI18n();
const native = canNativeShare();

const link = computed(() =>
  withAttribution(props.path, {
    title: "",
    path: props.path,
    inviterNo: props.inviterNo,
    merchantNo: props.merchantNo,
  }));

/**
 * H5 复制。**复制的是带归因的那一份** —— 复制一个光秃秃的链接等于把归因丢了，
 * 而丢了之后没有任何症状：人照样进来，只是算不到分享他的那个人头上。
 */
function copy() {
  uni.setClipboardData({
    data: link.value,
    success: () => uni.showToast({ title: t("share.copied"), icon: "none" }),
  });
}
</script>

<template>
  <view class="shareact sh-center" :class="{ 'is-compact': compact }" @tap="native ? undefined : copy()">
    <sh-icon name="share" :size="compact ? 32 : 28" color="var(--sh-ink)"></sh-icon>
    <text class="txt-caption sh-muted">{{ $t(native ? "share.act" : "share.copy") }}</text>
    <!-- 小程序：原生转发按钮盖在整块上，自己不占视觉 -->
    <button v-if="native" class="shareact__native" open-type="share"></button>
  </view>
</template>

<style scoped>
/*
 * **宽度与方向由组件自己定**，不靠调用点给 class：小程序里调用点写的 class
 * 停在宿主节点上，组件根拿不到它 —— 于是同一份样式在 H5 对、在真机上散架。
 * 与 biz-coupon-strip 踩过的是同一个坑。
 */
.shareact {
  position: relative;
  flex-shrink: 0;
  flex-direction: column;
  gap: 4rpx;
}
.shareact.is-compact {
  gap: 2rpx;
  width: 72rpx;
}
.shareact__native {
  position: absolute;
  inset: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  border: none;
  opacity: 0;
}
</style>
