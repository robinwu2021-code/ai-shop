<script setup lang="ts">
// 领券中心 + 我的券包（同一页两 tab）。
// 不做两个页面：券的信息结构完全一样，只是「领没领」不同；
// 分成两页会让用户领完券还要自己找到另一个入口去看。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useI18n } from "vue-i18n";
import { isoDate, money } from "@shared/utils/format";
import type { Coupon, MyStoreCoupon } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const tab = ref<"center" | "mine">("center");
const coupons = ref<Coupon[]>([]);
const busy = ref("");

const now = Date.now();
const available = computed(() => coupons.value.filter((c) => !c.received && c.endAt > now));

/**
 * 券面上的那个大字。**两种券要分开写** ——
 * 满减券是「减 5 元」，折扣券是「八五折」，一个金额字段表达不了后者。
 * 契约此前只有一个 `discountMinor`，于是折扣券要么显示成 ¥0，要么显示成面额。
 */
function faceText(c: Coupon): string {
  return c.type === "DISCOUNT"
    ? String(t("coupon.rate", { n: (c.discountRate / 1000).toFixed(1) }))
    : money(c.faceMinor);
}
const mine = computed(() => coupons.value.filter((c) => c.received));
const shown = computed(() => (tab.value === "center" ? available.value : mine.value));

/**
 * 商家发给我的券（新模型）。**与领券中心那批分开显示**：
 * 那批是自己领的，这批是被动收到的；而且这批里有到店券 —— 要出示码、
 * 有次卡余次，混在一起会让人以为到店券也能在结算时抵扣。
 */
const storeCoupons = ref<MyStoreCoupon[]>([]);
const mineUsable = computed(() => storeCoupons.value.filter((c) => c.usableNow));
const mineDead = computed(() => storeCoupons.value.filter((c) => !c.usableNow));

async function load() {
  const [list, mineList] = await Promise.all([
    api.couponList(),
    // 没登录时这条会 401，券包空着就好，不该把整页搞挂
    api.myStoreCoupons().catch(() => [] as MyStoreCoupon[]),
  ]);
  coupons.value = list;
  storeCoupons.value = mineList;
}

/**
 * 把码放大给店员看。**用 showModal 而不是跳一页**：
 * 顾客是把手机递过去的，多一次跳转就多一次「返回键按错」。
 * 码里去掉了 0/O/1/I/L，店员照着屏幕手输不会认错。
 */
function showCode(c: MyStoreCoupon) {
  if (!c.redeemCode) return;
  // `showCancel: false` 对应 `alert: true` —— 只有一个「知道了」，没有取消
  void confirm({
    title: c.title,
    hint: String(t("coupon.codeBody", { code: c.redeemCode, n: c.remaining })),
    confirmText: String(t("coupon.codeClose")),
    alert: true,
  });
}

async function receive(c: Coupon) {
  if (c.received || busy.value) return;
  busy.value = c.couponNo;
  try {
    await api.receiveCoupon(c.couponNo);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = "";
  }
}

function expired(c: Coupon) {
  return c.endAt <= now;
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="coupon.title">
    <sh-tabs
      :items="[
        { key: 'center', label: String($t('coupon.center', { n: available.length })) },
        { key: 'mine', label: String($t('coupon.mine', { n: mine.length + mineUsable.length })) },
      ]"
      :active="tab"
      @change="(k: string) => (tab = k as typeof tab)"
    ></sh-tabs>

    <!--
      商家发给我的券。放在券包顶部：它们是**别人主动发过来的**，
      用户不知道自己有，藏在下面等于没发。
    -->
    <template v-if="tab === 'mine'">
      <view v-for="c in mineUsable" :key="c.userCouponNo" class="sh-card ticket sh-row">
        <view class="ticket__amount">
          <text class="txt-display ticket__v sh-num">{{ c.benefitText }}</text>
          <text class="txt-caption ticket__cond sh-num">
            {{ c.minAmountMinor
              ? $t("coupon.threshold", { p: money(c.minAmountMinor) })
              : $t("coupon.noThreshold") }}
          </text>
        </view>

        <view class="sh-fill">
          <text class="txt-strong ticket__name">{{ c.title }}</text>
          <!-- 到店券要说清「不能在结算时抵扣」，否则顾客会在收银台等着自动减 -->
          <text v-if="c.redeemMode === 'STORE_CODE'" class="txt-caption ticket__scope">
            {{ $t("coupon.storeOnly") }}
            <template v-if="c.timesTotal > 1">
              · {{ $t("coupon.remaining", { n: c.remaining, m: c.timesTotal }) }}
            </template>
          </text>
          <text class="txt-caption ticket__exp sh-num">{{ $t("coupon.until", { d: isoDate(c.expireAt) }) }}</text>
        </view>

        <view v-if="c.redeemCode" class="txt-caption txt-bold ticket__btn" @tap="showCode(c)">
          {{ $t("coupon.showCode") }}
        </view>
        <text v-else class="txt-caption ticket__state ticket__state--ok txt-primary">{{ $t("coupon.autoUse") }}</text>
      </view>

      <!-- 过期/用完的折叠在下面，但**不删掉**：券包里少一张，用户会以为平台吞了它 -->
      <view v-for="c in mineDead" :key="c.userCouponNo" class="sh-card ticket is-expired sh-row">
        <view class="ticket__amount">
          <text class="txt-display ticket__v sh-num">{{ c.benefitText }}</text>
        </view>
        <view class="sh-fill">
          <text class="txt-strong ticket__name">{{ c.title }}</text>
          <text class="txt-caption ticket__exp sh-num">{{ $t("coupon.until", { d: isoDate(c.expireAt) }) }}</text>
        </view>
        <text class="txt-caption ticket__state">
          {{ c.remaining <= 0 ? $t("coupon.usedUp") : $t("coupon.expired") }}
        </text>
      </view>
    </template>

    <!-- 券的形状：左边金额、右边规则，中间用色块分隔而不是虚线（扁平风） -->
    <view
      v-for="c in shown"
      :key="c.couponNo"
      class="sh-card ticket sh-row"
      :class="{ 'is-expired': expired(c) }"
    >
      <view class="ticket__amount">
        <text class="txt-display ticket__v sh-num">{{ faceText(c) }}</text>
        <text class="txt-caption ticket__cond sh-num">
          {{ c.thresholdMinor
            ? $t("coupon.threshold", { p: money(c.thresholdMinor) })
            : $t("coupon.noThreshold") }}
        </text>
      </view>

      <view class="sh-fill">
        <text class="txt-strong ticket__name">{{ c.title }}</text>
        <text class="txt-caption ticket__scope">{{ c.scopeDesc }}</text>
        <text class="txt-caption ticket__exp sh-num">{{ $t("coupon.until", { d: isoDate(c.endAt) }) }}</text>
      </view>

      <view
        v-if="!c.received"
        class="txt-caption txt-bold ticket__btn"
        :class="{ 'is-busy': busy === c.couponNo }"
        @tap="receive(c)"
      >
        {{ $t("coupon.receive") }}
      </view>
      <text v-else-if="expired(c)" class="txt-caption ticket__state">{{ $t("coupon.expired") }}</text>
      <text v-else class="txt-caption ticket__state ticket__state--ok txt-primary">{{ $t("coupon.got") }}</text>
    </view>

    <sh-empty
      bare
      v-if="!shown.length && !(tab === 'mine' && storeCoupons.length)" :text='tab === "center" ? $t("coupon.centerEmpty") : $t("coupon.mineEmpty")'></sh-empty>
  </sh-scaffold>
</template>

<style scoped>
/* 面色 / 圆角 / 内边距交给 `.sh-card` —— 此前这三行是把它照抄了一遍。
   内边距因此从 28rpx 变成 C 端的密度档 32rpx（`--sh-pad-card` 没被 C 端覆盖），
   差 2px：**那正是密度变量存在的意义** —— 各页各写一个数，调密度时就得逐页找。 */
.ticket {
  gap: 24rpx;
  margin-bottom: 20rpx;
}
.ticket.is-expired {
  opacity: 0.5;
}
.ticket__amount {
  flex: 0 0 auto;
  min-width: 150rpx;
  background: var(--sh-danger-tint);
  border-radius: 24rpx;
  padding: 22rpx 16rpx;
  text-align: center;
}
.ticket__v {
  display: block;
  color: var(--sh-danger);
}
.ticket__cond {
  display: block;
  color: var(--sh-danger);
  margin-top: 8rpx;
}

.ticket__name {
  display: block;
}
.ticket__scope {
  display: block;
  margin-top: 8rpx;
}
.ticket__exp {
  display: block;
  margin-top: 8rpx;
}
.ticket__btn {
  flex: 0 0 auto;
  padding: 16rpx 32rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
}
.ticket__btn.is-busy {
  opacity: 0.5;
}
.ticket__state {
  flex: 0 0 auto;
}
</style>
