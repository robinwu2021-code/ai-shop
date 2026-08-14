<script setup lang="ts">
// 我的（复用 C 端的外观面板：4 皮肤 × 明暗 × 三语 × 多市场）。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useMerchantStore } from "@/stores/merchant";
import { refreshUnread, unreadCount } from "@/stores/messages";
import { ROUTES } from "@/shared/nav";

const merchant = useMerchantStore();
const sheetOpen = ref(false);

const statusKey = computed(() => `me.status${merchant.profile?.status ?? "NONE"}`);

function go(url: string) {
  uni.navigateTo({ url });
}

function goLogin() {
  uni.navigateTo({ url: ROUTES.login });
}

function later() {
  uni.showToast({ title: "该功能在后续批次交付", icon: "none" });
}

async function logout() {
  // 解绑要在清令牌**之前** —— 之后就没有可用的令牌了。
  // 门店共用一台手机换班时，上一班的人不该继续收到这家店的订单推送
  await merchant.unbindPushDevice();
  merchant.logout();
  uni.showToast({ title: "已退出", icon: "none" });
}

onShow(() => {
  void merchant.loadProfile().catch(() => null);
  // 从消息页返回时角标要立即回落，不等下一轮 30s 轮询
  void refreshUnread();
});
</script>

<template>
  <sh-scaffold title-key="tab.me" tab="me">
    <view v-if="!merchant.isLogin" class="sh-card head" @tap="goLogin">
      <text class="sh-h2">{{ $t("me.notLogin") }}</text>
      <text class="sh-muted">{{ $t("me.notLoginHint") }}</text>
    </view>

    <view v-else class="sh-card head">
      <text class="head__logo">{{ merchant.profile?.logo || "🏪" }}</text>
      <view class="head__main">
        <text class="sh-h2">{{ merchant.profile?.name || $t("me.store") }}</text>
        <text class="sh-chip" :class="merchant.isActive ? 'sh-chip--primary' : 'sh-chip--warning'">
          {{ $t(statusKey) }}
        </text>
      </view>
    </view>

    <!--
      分组密排，不是一行一张卡。
      原先每一行都套 sh-card：卡片自带内边距、圆角与投影，五行就变成五块互不相干的浮起色块，
      中间的留白比行本身还显眼 —— 「看起来像五个功能模块」，而它们其实只是一张设置清单。
      改成组内密排（同 C 端「我的」）：**分组表达归属，间距只在组与组之间**。
    -->
    <view class="cells">
      <!-- 结算与经营数据按 perms 裁剪：店员看不到「钱」，也看不到客户资产 -->
      <view v-if="merchant.can('biz:finance')" class="cell" @tap="go(ROUTES.settle)">
        <text class="cell__label">{{ $t("me.settle") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <!--
        员工与授权。**唯一入口原先在工作台第二屏**，用户实际反馈是「没看到添加员工的功能」。
        放这里而不是往工作台上提：工作台按「今天要干的活」排，加员工不是每天干的事。
      -->
      <view v-if="merchant.can('biz:store:admin')" class="cell" @tap="go(ROUTES.staff)">
        <text class="cell__label">{{ $t("me.staff") }}</text>
      </view>
      <view v-if="merchant.can('biz:customer')" class="cell" @tap="go(ROUTES.stats)">
        <text class="cell__label">{{ $t("me.stats") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
    </view>

    <view class="cells">
      <!-- 消息：新订单/售后/评价的落点。红点数与 tabBar 角标同源（30s 轮询） -->
      <view v-if="merchant.isLogin" class="cell" @tap="go(ROUTES.messages)">
        <text class="cell__label">{{ $t("me.messages") }}</text>
        <text v-if="unreadCount" class="cell__badge sh-num">
          {{ unreadCount > 99 ? "99+" : unreadCount }}
        </text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <view class="cell" @tap="sheetOpen = true">
        <text class="cell__label">{{ $t("me.appearance") }}</text>
        <text class="cell__value">{{ $t("me.appearanceValue") }}</text>
      </view>
      <view class="cell" @tap="later">
        <text class="cell__label">{{ $t("me.help") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
    </view>

    <!-- 退出登录单独一组：它与上面几项不是同类，紧挨着放容易误点 -->
    <view v-if="merchant.isLogin" class="cells">
      <view class="cell" @tap="logout">
        <text class="cell__label cell__label--danger">{{ $t("me.logout") }}</text>
      </view>
    </view>

    <sh-theme-sheet v-model:visible="sheetOpen"></sh-theme-sheet>
  </sh-scaffold>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  gap: 24rpx;
  margin-bottom: 24rpx;
}
.head__logo {
  font-size: 64rpx;
  width: 108rpx;
  height: 108rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  text-align: center;
  line-height: 108rpx;
}
.head__main {
  flex: 1;
}
.head__main .sh-chip {
  margin-top: 12rpx;
}
/* 组：组间留白，组内不留 —— 归属靠分组表达，不靠每行浮起 */
.cells {
  margin-top: 20rpx;
  display: flex;
  flex-direction: column;
  gap: 2rpx;
  background: var(--sh-surface);
  border-radius: 24rpx;
  overflow: hidden;
}
.cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
  padding: 26rpx;
}
.cell__label {
  font-size: 28rpx;
  color: var(--sh-ink);
  flex-shrink: 0;
}
/* 退出登录用警示色：它是不可逆动作，和「查看结算单」不该长得一样 */
.cell__label--danger {
  color: var(--sh-danger);
}
.cell__badge {
  flex-shrink: 0;
  min-width: 32rpx;
  height: 32rpx;
  line-height: 32rpx;
  padding: 0 8rpx;
  border-radius: 9999px;
  background: var(--sh-danger);
  color: #fff;
  /* 字阶最小档。20rpx 不在字阶上，且「99+」在小屏上本来就快挤不下了 */
  font-size: 24rpx;
  text-align: center;
}
.cell__value {
  font-size: 26rpx;
  color: var(--sh-sub);
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
