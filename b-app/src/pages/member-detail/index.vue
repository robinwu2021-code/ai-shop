<script setup lang="ts">
// 会员详情（P1）。三块：他是谁、各店往来、他是怎么来的。
//
// **「谁发的链接」必须写出来**：只记「来自分享」的话，分享激励没法结算，
// 商家也不知道该谢谁 —— 而那句「李姐帮我拉来的」正是他会记住的东西。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import { monthDay } from "@shared/utils/datetime";
import type { MemberDetail } from "@shared/types";

const merchant = useMerchantStore();
const data = ref<MemberDetail | null>(null);
const memberNo = ref("");

/** 多店主体才显示「各店往来」—— 单店时它与上面的总数一模一样 */
const showStores = computed(() => merchant.multiStore && (data.value?.stores.length ?? 0) > 0);

function storeName(no?: string | null) {
  if (!no) return "—";
  return merchant.stores.find((s) => s.storeNo === no)?.name || no;
}

async function load() {
  try {
    data.value = await api.mMemberDetail(memberNo.value);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

onLoad(async (q) => {
  memberNo.value = q?.memberNo ?? "";
  await merchant.ensureStores().catch(() => null);
  await load();
});
</script>

<template>
  <sh-scaffold title-key="memberDetail.title" :denied="!merchant.can('biz:customer')">
    <template v-if="data">
      <view class="sh-card">
        <view class="row">
          <text class="name">···{{ data.member.phoneTail || "----" }}</text>
          <text v-if="data.member.level" class="sh-chip"
            :class="data.member.level === 'SLEEPING' ? 'sh-chip--warning' : 'sh-chip--primary'">
            {{ $t(`members.level.${data.member.level}`) }}
          </text>
        </view>
        <text class="sh-muted mt">
          {{ $t("memberDetail.joined", { s: monthDay(data.member.joinedAt) }) }}
          · {{ $t(`members.source.${data.member.source}`) }}
        </text>
        <text v-if="data.member.firstStoreNo" class="sh-muted">
          {{ $t("memberDetail.firstStore", { s: storeName(data.member.firstStoreNo) }) }}
        </text>
        <sh-kv between :label="String($t('memberDetail.lifetime'))" class="kv mt">
          <text class="sh-num val">
            {{ $t("members.stat", {
              n: data.member.orderCount, m: money(data.member.totalSpentMinor) }) }}
          </text>
        </sh-kv>
        <sh-kv between :label="String($t('memberDetail.d90'))" class="kv">
          <text class="sh-num val">{{ data.member.d90OrderCount }}</text>
        </sh-kv>
      </view>

      <!-- 各店往来：多店商家问的是「南门店有多少熟客」，单店没有这个问题 -->
      <view v-if="showStores" class="sh-card mt-card">
        <text class="sh-h2">{{ $t("memberDetail.stores") }}</text>
        <view v-for="s in data.stores" :key="s.storeNo" class="kv line">
          <text>
            {{ storeName(s.storeNo) }}
            <text v-if="s.isFirstStore" class="sh-chip">{{ $t("memberDetail.firstTag") }}</text>
          </text>
          <text class="sh-num val">
            {{ $t("members.stat", { n: s.orderCount, m: money(s.totalSpentMinor) }) }}
          </text>
        </view>
      </view>

      <!-- 来源轨迹：谁发的链接、哪个员工录的，都要写出来 -->
      <view class="sh-card mt-card">
        <text class="sh-h2">{{ $t("memberDetail.sources") }}</text>
        <view v-for="(s, i) in data.sources" :key="i" class="kv line">
          <text>
            {{ monthDay(s.occurredAt) }} · {{ $t(`members.source.${s.sourceType}`) }}
          </text>
          <text class="sh-muted">
            <template v-if="s.inviterUserNo">
              {{ $t("memberDetail.byInviter", { s: s.inviterUserNo }) }}
            </template>
            <template v-else-if="s.operatorNo">
              {{ $t("memberDetail.byOperator", { s: s.operatorNo }) }}
            </template>
            <template v-else>{{ storeName(s.storeNo) }}</template>
          </text>
        </view>
      </view>

      <text class="tip">{{ $t("members.privacyHint") }}</text>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.name {
  font-size: 34rpx;
  font-weight: 600;
}
.mt {
  display: block;
  margin-top: 8rpx;
}
.mt-card {
  margin-top: 16rpx;
}
/* 只留本页版面：排法（两端对齐）归 sh-kv */
.kv {
  font-size: 26rpx;
  padding: 6rpx 0;
}
.kv.line {
  border-top: 2rpx solid var(--sh-faint);
  padding-top: 12rpx;
  margin-top: 12rpx;
}
.val {
  font-weight: 600;
}
.tip {
  display: block;
  margin-top: 24rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
