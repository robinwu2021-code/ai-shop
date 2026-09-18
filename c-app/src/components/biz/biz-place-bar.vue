<script setup lang="ts">
/**
 * 「当前位置」那一行 —— **四处共用的同一件**：
 * 首页顶栏之下、收货地址页、下单页、选择地点页。
 *
 * <p><b>为什么要有它</b>：此前这一行在每一页各画一遍，而四处的形状、动作、
 * 甚至地名的取法都不一样。实测截图里两页并排显示的不是同一个位置 ——
 * 收货地址页写着「桂澜新村」（几天前绑的归属），选择地点页写着「使用当前位置」。
 *
 * <p><b>一行不是一块。</b> 早先收货地址页的空态给的是整块卡 + 整条实心大按钮，
 * 底部还有一条同样大的「新增地址」—— 一屏两个同等份量的主按钮。
 * 「存为地址」不是这一页的主动作（主动作是「挑一条地址去下单」），
 * 所以它是一个文字动作，不是按钮。
 */
import { useI18n } from "vue-i18n";

defineProps<{
  /** 地名。空串时回落到「当前位置」四个字 —— **宁可少一行，不要编一个地名** */
  name?: string;
  /** 第二行的补充说明（「这一带还没有你存过的地址」之类）。不给就不占位 */
  sub?: string;
  /** 这个地名可能不是最新的（地图挂了、用的是库里旧的那条） */
  stale?: boolean;
  /** 显不显示「存为地址」。选择地点页不需要它 */
  canSave?: boolean;
}>();

const emit = defineEmits<{
  (e: "relocate"): void;
  (e: "save"): void;
}>();

const { t } = useI18n();
</script>

<template>
  <view class="sh-card bar">
    <view class="sh-row bar__head">
      <sh-icon name="pin" :size="24" color="var(--sh-primary)"></sh-icon>
      <text class="txt-caption txt-primary">{{ t("address.youAreHere") }}</text>
      <text class="sh-fill"></text>
      <!--
        **「重新定位」在哪儿点都是同一个动作**（store 的 relocate）。
        没有它的话，位置一旦落错就只能等过期，而用户不知道要等。
      -->
      <text class="txt-caption txt-primary sh-hit" @tap.stop="emit('relocate')">
        {{ t("home.relocate") }}
      </text>
    </view>
    <view class="sh-row bar__body">
      <view class="sh-fill bar__text">
        <text class="txt-strong bar__name">{{ name || t("address.youAreHere") }}</text>
        <text v-if="sub" class="txt-caption bar__sub">{{ sub }}</text>
      </view>
      <!-- 文字动作，不是按钮：它不是这一页的主动作 -->
      <text v-if="canSave" class="txt-caption txt-primary bar__save sh-hit" @tap.stop="emit('save')">
        {{ t("address.saveAsAddressShort") }}
      </text>
    </view>
    <!-- 地图挂了、用的是库里旧的那条 —— 要说出来，不能让人以为它是刚测的 -->
    <text v-if="stale" class="sh-hint">{{ t("address.placeStale") }}</text>
  </view>
</template>

<style scoped>
.bar__head {
  gap: 8rpx;
}
.bar__body {
  align-items: flex-end;
  gap: 24rpx;
  margin-top: 8rpx;
}
.bar__text {
  min-width: 0;
}
.bar__name {
  display: block;
}
.bar__sub {
  display: block;
  margin-top: 8rpx;
}
.bar__save {
  flex-shrink: 0;
}
</style>
