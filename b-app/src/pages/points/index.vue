<script setup lang="ts">
// 积分成本与开关（B-11.11）。
//
// **开关与成本必须在同一屏。** 积分开关是「商家自己开」的，
// 而在这一页之前他看不到自己发分花了多少钱 —— 让人在看不到代价的情况下做决定。
//
// 商家**不感知积分抵扣**（V34：他按订单全额收款，抵扣的成本由平台承担），
// 所以这里只有「我发分花了多少」，没有「我收到多少分」—— 后者在他这儿不存在。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import type { MerchantPointAccount } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

/** 与后端同一个码：`/biz/points/**` 三条都要 `biz:finance` */
const canEdit = computed(() => merchant.can("biz:finance"));

const account = ref<MerchantPointAccount | null>(null);
const busy = ref(false);

/**
 * 开关能不能动。三种关法要分得开，**因为该找谁是不一样的**：
 *   · `forced`         平台强制开启 → 商家关不掉，说明原因就行
 *   · `disabledReason` 上级开关关着 → 他自己开不了，要去找平台/社区
 *   · 其余             他自己说了算
 */
const lockedReason = computed(() => {
  if (!account.value) return "";
  if (account.value.forced) return t("points.forced");
  return account.value.disabledReason || "";
});

async function load() {
  account.value = await api.mPointsAccount();
}

async function toggle() {
  if (!account.value || busy.value || !canEdit.value || lockedReason.value) return;
  busy.value = true;
  try {
    account.value = await api.mPointsToggle({ enabled: !account.value.enabled });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/** 路由写在这儿而不是 shared 的 ROUTES：那一份是三端共用的，只放三端都有的页 */
function openRecords() {
  uni.navigateTo({ url: "/pages/points-records/index" });
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="points.title" :denied="!canEdit">
    <template v-if="account">
      <view class="sh-card">
        <text class="sh-muted">{{ $t("points.periodExpense") }}</text>
        <text class="amt sh-num">{{ money(account.periodExpenseMinor) }}</text>
        <text class="sh-muted sub">
          {{ $t("points.periodHint", { p: account.period }) }}
        </text>
      </view>

      <view class="sh-card mt">
        <view class="line" @tap="toggle">
          <text class="sh-h2">{{ $t("points.switch") }}</text>
          <view class="sw" :class="{ 'sw--on': account.enabled, 'sw--lock': !!lockedReason }" />
        </view>
        <text class="sh-muted sub">{{ $t("points.switchHint") }}</text>
        <!--
          ⚠️ **关不掉时要说清是哪一种。** 只把开关灰掉的话，商家不知道
          该找平台开、还是本来就关不掉 —— 而这两件事的下一步动作完全不同。
        -->
        <text v-if="lockedReason" class="locked">{{ lockedReason }}</text>
        <text class="sh-muted sub">{{ $t("points.closeOnlyFuture") }}</text>
      </view>

      <!--
        发放规则由平台按类目统一配，商家侧**只读展示**。
        依据是实测：线上 199 件商品里，用商品级配置配了积分的是 0 件 ——
        一个 0% 填充率的配置项不是「灵活」，是「没人用」。
      -->
      <view class="sh-card mt">
        <view class="line">
          <text class="sh-h2">{{ $t("points.rule") }}</text>
          <text class="sh-chip">{{ $t("points.rulePlatform") }}</text>
        </view>
        <text class="sh-muted sub">{{ $t("points.ruleHint") }}</text>
      </view>

      <view class="sh-btn sh-btn--muted mt" @tap="openRecords">
        {{ $t("points.records") }}
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.mt { margin-top: 16rpx; }
.amt {
  display: block;
  margin-top: 8rpx;
  font-size: 60rpx;
  font-weight: 700;
  line-height: 1.2;
  color: var(--sh-ink);
}
.sub {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  line-height: 1.5;
}
.line {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.locked {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--sh-primary-text);
}
.sw {
  width: 84rpx;
  height: 48rpx;
  border-radius: 24rpx;
  background: var(--sh-line);
  position: relative;
  flex: none;
}
.sw::after {
  content: "";
  position: absolute;
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  background: #fff;
  top: 6rpx;
  left: 6rpx;
  transition: left 0.15s;
}
.sw--on { background: var(--sh-primary); }
.sw--on::after { left: 42rpx; }
/* 锁住时降饱和 —— 与「关着」在视觉上要分得开 */
.sw--lock { opacity: 0.45; }
</style>
