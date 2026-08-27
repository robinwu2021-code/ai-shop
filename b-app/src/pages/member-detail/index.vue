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
        <view class="sh-row row">
          <text class="txt-title">···{{ data.member.phoneTail || "----" }}</text>
          <text v-if="data.member.level" class="sh-chip"
            :class="data.member.level === 'SLEEPING' ? 'sh-chip--warning' : 'sh-chip--primary'">
            {{ $t(`members.level.${data.member.level}`) }}
          </text>
        </view>
        <text class="sh-muted sh-mt-xs blk">
          {{ $t("memberDetail.joined", { s: monthDay(data.member.joinedAt) }) }}
          · {{ $t(`members.source.${data.member.source}`) }}
        </text>
        <text v-if="data.member.firstStoreNo" class="sh-muted">
          {{ $t("memberDetail.firstStore", { s: storeName(data.member.firstStoreNo) }) }}
        </text>
        <sh-kv between :label="String($t('memberDetail.lifetime'))" class="txt-sub kv sh-mt-xs blk">
          <text class="sh-num val">
            {{ $t("members.stat", {
              n: data.member.orderCount, m: money(data.member.totalSpentMinor) }) }}
          </text>
        </sh-kv>
        <sh-kv between :label="String($t('memberDetail.d90'))" class="txt-sub kv">
          <text class="sh-num val">{{ data.member.d90OrderCount }}</text>
        </sh-kv>
      </view>

      <!-- 各店往来：多店商家问的是「南门店有多少熟客」，单店没有这个问题 -->
      <view v-if="showStores" class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t("memberDetail.stores") }}</text>
        <view v-for="s in data.stores" :key="s.storeNo" class="txt-sub kv line">
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
      <view class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t("memberDetail.sources") }}</text>
        <view v-for="(s, i) in data.sources" :key="i" class="txt-sub kv line">
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

      <text class="sh-hint sh-mt-md">{{ $t("members.privacyHint") }}</text>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.row {
  gap: 12rpx;
}

.blk {
  display: block;
}
/* 只留本页版面：排法（两端对齐）归 sh-kv */
.kv {
  padding: 6rpx 0;
}
.kv.line {
  border-top: var(--sh-hairline-soft);
  padding-top: 12rpx;
  margin-top: 12rpx;
}
.val {
  font-weight: 600;
}

</style>
