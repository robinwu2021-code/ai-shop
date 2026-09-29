<script setup lang="ts">
/*
 * 「开店请下载虹选商家 App」。**入驻那一屏有两处用它**：报名表底部与提交完成页，
 * 抄两份的话加一个二维码就要改两处，而少改的那一处没人会注意到。
 *
 * 三样东西，逐样都有理由：
 * · 按本机系统给当前那一档（iOS / Android），点一下复制链接；
 * · 另一档收成一行小字 —— 换台设备装的人要得到它，但它不该和主路并排；
 * · 二维码**默认收着**：人正拿着这台手机看这一屏，自己扫不了自己的屏幕。
 *   它的用处是给旁边的人扫、或者用另一台设备装，所以是「需要时点开」，
 *   不是一上来就占掉半屏。
 *
 * 二维码用点阵铺 `<view>` 而不是画 canvas：三端的 canvas 不是同一套 API，
 * 画在上面就要写三份（见 `@/shared/qrcode` 的说明）。
 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { qrMatrix } from "@/shared/qrcode";

const props = defineProps<{
  /** 当前系统那一档的下载地址。必给 —— 调用方已经兜过底（退回官网下载页） */
  link: string;
  /** 另一档。空 = 那一档还没有，整行不显示 */
  otherLink?: string;
  /** 当前系统是不是 iOS，决定两行文案谁在上 */
  ios: boolean;
  /**
   * 主按钮用实心还是弱一档。**提交完成那一屏给 true** ——
   * 那里「去装 App」是唯一的下一步；报名表底部它只是附带说明，
   * 实心会和「提交报名」抢，而那一屏真正要人点的是提交。
   */
  primary?: boolean;
}>();

const { t } = useI18n();
const qrVisible = ref(false);

/** 扫出来是**当前这一档** —— 与上面那颗按钮复制的是同一个地址，不然两条路给的东西不一样 */
const matrix = computed(() => qrMatrix(props.link));

/**
 * 每格多少 rpx。**算出来而不是交给 CSS** —— 小程序端 `aspect-ratio` 要看基础库版本，
 * 而 `flex: 1` 分不出整数像素时相邻格子会差半像素，密的码就此扫不出来。
 * 372 = 420（整块）− 24×2（留白）。除不尽时向下取整，整块因此略小于 372，居中即可。
 */
const cell = computed(() => (matrix.value.length ? Math.floor((372 / matrix.value.length) * 100) / 100 : 0));

function copy(url: string) {
  uni.setClipboardData({
    data: url,
    success: () => uni.showToast({ title: String(t("merchant.appLinkCopied")), icon: "none" }),
  });
}
</script>

<template>
  <view class="getapp">
    <text class="txt-body getapp__title">{{ $t("merchant.getAppTitle") }}</text>
    <view class="sh-btn getapp__btn" :class="{ 'sh-btn--soft': !props.primary }" @tap="copy(props.link)">
      {{ $t(props.ios ? "merchant.getAppIos" : "merchant.getAppAndroid") }}
    </view>
    <text v-if="props.otherLink" class="sh-link getapp__other" @tap="copy(props.otherLink)">
      {{ $t(props.ios ? "merchant.getAppAndroid" : "merchant.getAppIos") }}
    </text>

    <text class="sh-link getapp__qrtoggle" @tap="qrVisible = !qrVisible">
      {{ $t(qrVisible ? "merchant.appQrHide" : "merchant.appQrShow") }}
    </text>
    <view v-if="qrVisible && matrix.length" class="getapp__qr">
      <!--
        一行一个 view、一格一个 view。**格子不留间距**，靠背景色区分 ——
        留了间距的码在小屏上会被识别成噪点。
      -->
      <view v-for="(row, r) in matrix" :key="r" class="qr__row" :style="{ height: cell + 'rpx' }">
        <view
          v-for="(dark, c) in row"
          :key="c"
          class="qr__cell"
          :class="{ 'qr__cell--on': dark }"
          :style="{ width: cell + 'rpx', height: cell + 'rpx' }"
        />
      </view>
    </view>
    <text v-if="qrVisible && matrix.length" class="txt-caption getapp__qrhint">
      {{ $t("merchant.appQrHint") }}
    </text>
  </view>
</template>

<style scoped>
/*
  这一段整体从 pages/me/index.vue 搬过来（两处曾各有一份）。
  **字号是 txt-body 不是 txt-caption**（2026-09-29）：
  这是店主提交完唯一要做的下一步，而此前它和脚注一样小。
*/
.getapp {
  margin-top: 40rpx;
  padding-top: 28rpx;
  border-top: var(--sh-hairline);
  text-align: center;
}
.getapp__title {
  display: block;
}
.getapp__btn {
  margin-top: 16rpx;
}
/* 另一档：本机系统之外那个，弱一档摆着 —— 有人在安卓机上给 iPhone 的同事拿链接 */
.getapp__other {
  display: block;
  margin-top: 16rpx;
}
.getapp__qrtoggle {
  display: block;
  margin-top: 16rpx;
}
/*
  白底留白（quiet zone）是**扫得出来的前提**，不是装饰：
  二维码规范要求四周留 4 格空白，贴着深色背景的码扫不出来。
*/
.getapp__qr {
  margin: 24rpx auto 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 420rpx;
  padding: 24rpx;
  background: #ffffff;
  border-radius: 8rpx;
}
.qr__row {
  display: flex;
}
/*
  **颜色写死黑白是有意的**，不走 --sh-* ：二维码要的是最高对比，
  换肤把它变成主题色或深色底就直接扫不出来了，而界面上看着仍然「有一张码」。
  这也是整块 .getapp__qr 自带白底的原因。
*/
.qr__cell {
  background: #ffffff;
}
.qr__cell--on {
  background: #000000;
}
.getapp__qrhint {
  display: block;
  margin-top: 12rpx;
  text-align: center;
}
</style>
