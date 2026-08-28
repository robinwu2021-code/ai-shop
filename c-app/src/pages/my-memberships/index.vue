<script setup lang="ts">
// 我的会员身份 + 每家一个消息开关（P7）。
//
// **这一页是「商家能给会员发消息」这件事的前提**：
// 顾客要能看到**谁在给他发消息**，并且能自己关掉。没有它就上线群发，
// 等于给了一个没有关闭按钮的喇叭。
//
// 为什么按「店」列而不是一个总开关：他可能愿意收张记生鲜的上新通知，
// 但不想再收另一家的。一个总开关只会让他把所有店一起关掉 —— 对商家更糟。
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { isoDate, money } from "@shared/utils/format";
import type { MyMembership } from "@shared/types";

const { t } = useI18n();

const list = ref<MyMembership[]>([]);
const busy = ref("");

async function load() {
  list.value = await api.myMemberships().catch(() => []);
}

/**
 * 关/开某一家店的消息。
 *
 * **关的时候不问，开的时候也不问** —— 这是他自己的选择，弹窗确认只会让人烦。
 * 但关掉之后要立刻看到状态变化，否则他会怀疑没生效而反复点。
 */
async function toggle(m: MyMembership) {
  if (busy.value) return;
  busy.value = m.entityNo;
  const next = !m.reachOptOut;
  try {
    await api.setMembershipReach(m.entityNo, next);
    m.reachOptOut = next;
    uni.showToast({
      title: String(next ? t("myMembership.offDone") : t("myMembership.onDone")),
      icon: "none",
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = "";
  }
}
onShow(load);
</script>

<template>
  <sh-scaffold title-key="myMembership.title">
    <sh-empty bare v-if="!list.length" :text="String($t('myMembership.empty'))"></sh-empty>

    <view v-for="m in list" :key="m.entityNo" class="sh-card card sh-row sh-row--between">
      <view class="card__main">
        <text class="txt-strong card__name">{{ m.entityName }}</text>
        <text class="txt-caption sh-muted card__d">
          {{ $t("myMembership.stat", { n: m.orderCount, m: money(m.totalSpentMinor) }) }}
          · {{ $t("myMembership.since", { d: isoDate(m.joinedAt) }) }}
        </text>
        <text class="txt-caption sh-muted card__d">
          {{ m.reachOptOut ? $t("myMembership.off") : $t("myMembership.on") }}
        </text>
      </view>
      <text
        class="sh-chip"
        :class="{ 'sh-chip--primary': !m.reachOptOut }"
        @tap="toggle(m)"
      >
        {{ m.reachOptOut ? $t("myMembership.turnOn") : $t("myMembership.turnOff") }}
      </text>
    </view>

    <text v-if="list.length" class="sh-hint sh-mt-sm">{{ $t("myMembership.hint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
/* 面色 / 圆角 / 内边距交给 `.sh-card` —— 此前这三行是把它照抄了一遍。
   内边距因此从 28rpx 变成 C 端的密度档 32rpx（`--sh-pad-card` 没被 C 端覆盖），
   差 2px：**那正是密度变量存在的意义** —— 各页各写一个数，调密度时就得逐页找。 */
.card {
  gap: 24rpx;
  margin-bottom: 20rpx;
}
.card__main {
  flex: 1;
}
.card__name {
  display: block;
}
.card__d {
  display: block;
  margin-top: 8rpx;
}
</style>
