<script setup lang="ts">
/**
 * 一条收货地址长什么样 —— **三处共用的同一件**：
 * 收货地址页（带动作）、下单页（只读）、选择地点页。
 *
 * <p><b>差别只在「带不带动作」，不在长相。</b> 所以它是一个组件加一个开关，
 * 不是两个组件 —— 此前下单页是「一张卡，点一下换」，收货地址页是
 * 「列表行 + 每条五个动作」，两处各写各的。同一条地址在两屏上的样子不一样时，
 * 用户要重新认一遍哪个是姓名、哪个是门牌。
 */
import type { Address } from "@shared/types";
import { useI18n } from "vue-i18n";

defineProps<{
  address: Address;
  /** 带不带底部那行动作（设为默认 / 编辑 / 删除）。下单页不带 */
  actions?: boolean;
  /** 标成「当前位置匹配到的那条」 */
  here?: boolean;
  /** 标成「正在用的那条」（下单页选中的） */
  active?: boolean;
  /**
   * 不要卡片外壳 —— 用在**别人的卡里面**（下单页的「收货信息」块）。
   *
   * <p>没有这一档的话下单页只能自己再画一遍，而那正是此前两处字段顺序
   * 与标注不一致的来源。卡套卡不是选项：它在小程序上会多出一圈阴影与圆角。
   */
  bare?: boolean;
  /** 右上角那句动作提示（下单页的「更换」）。不给就不占位 */
  more?: string;
}>();

const emit = defineEmits<{
  (e: "tap"): void;
  (e: "edit"): void;
  (e: "remove"): void;
  (e: "default"): void;
  (e: "fix"): void;
}>();

const { t } = useI18n();
</script>

<template>
  <view :class="bare ? 'card card--bare' : 'sh-card card'" @tap="emit('tap')">
    <view class="card__head sh-wrap">
      <text class="txt-strong">{{ address.name }}</text>
      <text class="txt-caption sh-num">{{ address.phone }}</text>
      <text v-if="address.tag" class="txt-caption sh-chip tiny">{{ address.tag }}</text>
      <text v-if="address.isDefault" class="txt-caption sh-chip sh-chip--primary tiny">
        {{ t("address.default") }}
      </text>
      <!-- 定位匹配到的那条：标出来即可，**点一下就切**，不弹窗不追问 -->
      <text v-if="here" class="txt-caption sh-chip sh-chip--primary tiny">
        {{ t("address.youAreHere") }}
      </text>
      <text class="sh-fill"></text>
      <text v-if="more" class="txt-caption card__more">{{ more }}</text>
    </view>
    <text class="txt-caption card__addr">
      {{ address.region }} {{ address.detail }} {{ address.houseNo }}
    </text>

    <!--
      **没坐标的地址要看得出来。** 此前只有编辑页里说一句 —— 而他根本不会去编辑
      一条「看起来好好的」地址。自送半径判不了、骑手导航打不开、按坐标算可见性
      推不出任何商家，三件事在列表上都看不出区别。
      动作直接给「地图选点」：说了问题就要给出路，否则这一条只是让人不安。
    -->
    <view v-if="address.latE6 == null || address.lngE6 == null"
          class="sh-notice sh-notice--warning card__nocoord sh-row sh-row--between"
          @tap.stop="emit('fix')">
      <text class="txt-caption sh-fill">{{ t("address.noCoordHint") }}</text>
      <text class="txt-caption card__fix">{{ t("address.pick") }}</text>
    </view>

    <view v-if="actions" class="card__ops sh-row">
      <!--
        **只保留状态标，不再单给一个「设为当前位置」按钮** —— 整张卡点一下就是切换，
        再摆一个按钮就是同一件事的第二个入口，而两个入口里总有一个会先坏掉、且没人发现。
      -->
      <text v-if="active" class="txt-caption txt-strong op txt-primary">{{ t("address.here") }}</text>
      <text v-if="!address.isDefault" class="txt-caption op txt-primary" @tap.stop="emit('default')">
        {{ t("address.setDefault") }}
      </text>
      <!-- 左边是状态与「设为默认」，右边是编辑/删除：两组语义不同，挤在一起读不出分组 -->
      <text class="sh-fill"></text>
      <text class="txt-caption op txt-primary" @tap.stop="emit('edit')">{{ t("address.edit") }}</text>
      <text class="txt-caption op is-danger" @tap.stop="emit('remove')">{{ t("address.remove") }}</text>
    </view>
  </view>
</template>

<style scoped>
.card__head {
  gap: 12rpx;
}
/* 用在别人的卡里：去掉外壳，只留内容的纵向节奏 */
.card--bare {
  padding: 0;
  background: transparent;
}
.card__more {
  flex-shrink: 0;
  color: var(--sh-primary-text);
}
.card__addr {
  display: block;
  margin-top: 8rpx;
  line-height: 1.5;
}
.card__nocoord {
  margin-top: 16rpx;
}
.card__fix {
  flex-shrink: 0;
  color: var(--sh-primary-text);
}
.card__ops {
  gap: 24rpx;
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: 2rpx solid var(--sh-line);
}
.op {
  padding: 4rpx 0;
}
.is-danger {
  color: var(--sh-danger);
}
.tiny {
  transform: scale(0.9);
}
</style>
