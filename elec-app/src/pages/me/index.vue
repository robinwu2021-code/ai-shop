<script setup lang="ts">
// 我的（底部菜单第四格）。账号、两个身份各自的入口、历史记录。
// 身份分两组写：买家那组谁都有；供应商那组成了供应商才出现，没成就只留一行「成为供应商」。
// 历史记录只记在这台手机上（搜过的词、看过的料号）；询价、求购那些有据可查的记录各有自己的页。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import { goLogin, tryLogin } from "@/shared/auth";
import { ROUTES, go } from "@/shared/routes";
import { recentSearches, viewedParts } from "@/shared/recent";
import { switchTab } from "@/shared/tabs";
import type { ElecMe } from "@shared/types";

const user = useUserStore();
const me = ref<ElecMe | null>(null);
const searchCnt = ref(0);
const viewedCnt = ref(0);

onShow(async () => {
  searchCnt.value = recentSearches().length;
  viewedCnt.value = viewedParts().length;
  if (!(await tryLogin())) {
    me.value = null;
    return;
  }
  const [m] = await Promise.all([api.me().catch(() => null), user.loadProfile().catch(() => null)]);
  me.value = m;
});

const phone = computed(() => {
  const p = user.user?.phone ?? "";
  return /^\d{11}$/.test(p) ? `${p.slice(0, 3)}****${p.slice(7)}` : p;
});
/** 绑没绑以 /elec/me 为准（主系统资料拉失败时 user.user 是空的，会把绑过的人错说成没绑） */
const bound = computed(() => me.value?.phoneBound ?? user.hasPhone);
const sup = computed(() => me.value?.supplier ?? null);
const newOffers = computed(() => me.value?.badges.rfqNewOffers ?? 0);
const dispatchPending = computed(() => me.value?.badges.dispatchPending ?? 0);
const stockExpiring = computed(() => me.value?.badges.stockExpiring ?? 0);
</script>

<template>
  <sh-scaffold title-key="title.me">
    <view class="sh-card sh-row account">
      <view class="sh-center avatar">
        <sh-icon name="userFilled" :size="48" color="var(--sh-primary-text)"></sh-icon>
      </view>
      <view v-if="user.isLogin" class="grow">
        <text class="txt-title sh-num block">{{ phone || (bound ? "已绑手机号" : "还没绑手机号") }}</text>
        <view class="sh-row tags">
          <text class="sh-chip">买家</text>
          <text v-if="sup" class="sh-chip" :class="sup.status === 'SUSPENDED' ? 'sh-chip--danger' : 'sh-chip--primary'">
            供应商 <text class="sh-num">{{ sup.maskCode }}</text>{{ sup.status === "SUSPENDED" ? " · 已暂停" : "" }}
          </text>
        </view>
      </view>
      <view v-else class="grow sh-row sh-row--between" @tap="goLogin">
        <text class="txt-title">未登录</text>
        <text class="sh-link">去登录 ›</text>
      </view>
      <text v-if="user.isLogin && !bound" class="sh-link" @tap="go(ROUTES.login, { need: 'phone' })">绑手机号</text>
    </view>

    <text class="txt-caption sh-muted group">买家</text>
    <view class="sh-cells">
      <view class="sh-cell sh-row sh-row--between" @tap="switchTab('rfq')">
        <text class="txt-body">我的询价</text>
        <text class="txt-caption" :class="newOffers ? 'txt-primary' : 'sh-muted'">{{ newOffers ? `${newOffers} 单有新报价` : "" }} ›</text>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.rfqCreate)">
        <text class="txt-body">新询价</text>
        <text class="txt-caption sh-muted">›</text>
      </view>
    </view>

    <text class="txt-caption sh-muted group">供应商</text>
    <view v-if="sup" class="sh-cells">
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.dispatches)">
        <text class="txt-body">求购</text>
        <text class="txt-caption" :class="dispatchPending ? 'txt-primary' : 'sh-muted'">{{ dispatchPending ? `${dispatchPending} 条待回` : "" }} ›</text>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.stocks)">
        <text class="txt-body">我的库存</text>
        <text class="txt-caption" :class="stockExpiring ? 'txt-primary' : 'sh-muted'">{{ stockExpiring ? `${stockExpiring} 行快到期` : "" }} ›</text>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.stockBatches)">
        <text class="txt-body">上传记录</text>
        <text class="txt-caption sh-muted">›</text>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.supplierProfile)">
        <text class="txt-body">供应商资料</text>
        <text class="txt-caption sh-muted">›</text>
      </view>
    </view>
    <view v-else class="sh-cells">
      <view class="sh-cell sh-row sh-row--between" @tap="switchTab('supply')">
        <text class="txt-body">成为供应商</text>
        <text class="txt-caption sh-muted">传库存，接求购 ›</text>
      </view>
    </view>

    <text class="txt-caption sh-muted group">历史记录</text>
    <view class="sh-cells">
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.history, { view: 'search' })">
        <text class="txt-body">搜过的</text>
        <text class="txt-caption sh-muted">{{ searchCnt ? `${searchCnt} 条` : "" }} ›</text>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.history, { view: 'viewed' })">
        <text class="txt-body">看过的料号</text>
        <text class="txt-caption sh-muted">{{ viewedCnt ? `${viewedCnt} 个` : "" }} ›</text>
      </view>
    </view>

    <el-tabbar active="me"></el-tabbar>
  </sh-scaffold>
</template>

<style scoped>
.account {
  gap: 24rpx;
}
.avatar {
  flex: none;
  width: 96rpx;
  height: 96rpx;
  border-radius: 9999px;
  background: var(--sh-primary-tint);
}
.grow {
  flex: 1;
  min-width: 0;
}
.block {
  display: block;
}
.tags {
  gap: 12rpx;
  margin-top: 12rpx;
  flex-wrap: wrap;
}
.group {
  display: block;
  padding: 36rpx 12rpx 12rpx;
}
</style>
