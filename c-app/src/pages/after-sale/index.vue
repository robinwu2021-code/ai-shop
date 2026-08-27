<script setup lang="ts">
// 售后申请。
// 生鲜的坏果包赔走的也是这条 —— 原因选「品质问题」+ 传图，小额自动通过（极速退）。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { chooseImages } from "@shared/ports/media";
import { ROUTES, TRADE_RULES } from "@shared/utils/constants";
import { money } from "@shared/utils/format";
import type { AfterSaleReason, AfterSaleType, Order } from "@shared/types";

const { t } = useI18n();

/**
 * 售后原因**取自后端**（`/mp/after-sale/reasons`），不再在端上硬编码。
 *
 * 此前这里写死了一份六个码的清单，而后端那份是七条且内容不同 ——
 * 两份各自漂移，运营改后端的，端上纹丝不动。
 * 拿不到时退回一份最小清单：售后入口不该因为一个列表接口挂掉而打不开。
 */
const FALLBACK: AfterSaleReason[] = ["DAMAGED", "MISSING", "QUALITY", "OTHER"];
const REASONS = ref<AfterSaleReason[]>(FALLBACK);

const order = ref<Order | null>(null);
/**
 * 售后类型默认「仅退款」—— 邻里生鲜绝大多数是坏了/少了，退回来没意义。
 * 退货退款是主动选择，不是默认路径。
 */
const type = ref<AfterSaleType>("REFUND_ONLY");
const TYPES: AfterSaleType[] = ["REFUND_ONLY", "RETURN_REFUND"];
/** 拼 key 后 `$t` 的类型收窄不住，包一层比在模板里写断言干净 */
const typeText = (tp: AfterSaleType, suffix = "") => String(t(`afterSale.type${tp}${suffix}`));
const reason = ref<AfterSaleReason | "">("");
const detail = ref("");
const images = ref<string[]>([]);
const submitting = ref(false);
const submitted = ref(false);

/** 小额自动通过：让用户提交前就知道会不会秒退，而不是提交后才发现 */
// 只对仅退款成立：要退货的，货还没回来就秒退等于白送
const instantRefund = computed(
  () =>
    !!order.value &&
    type.value === "REFUND_ONLY" &&
    (order.value.amount.paidMinor || order.value.amount.payableMinor) <=
      TRADE_RULES.instantRefundMaxMinor,
);

const canSubmit = computed(() => !!reason.value && !submitting.value);

async function load(orderNo: string) {
  order.value = await api.orderDetail(orderNo);
}

async function pickImages() {
  try {
    const paths = await chooseImages(3);
    images.value = [...images.value, ...paths].slice(0, 3);
  } catch {
    // 用户取消，不提示
  }
}

async function submit() {
  const o = order.value;
  if (!o || !canSubmit.value) return;
  submitting.value = true;
  try {
    const label = String(t(`afterSale.reason.${reason.value}`));
    // 返回的是售后单，不是订单 —— 赋给 order 会把这一页的商品与金额清空
    await api.applyAfterSale(
      o.orderNo,
      detail.value ? `${label}：${detail.value}` : label,
      images.value,
      type.value,
    );
    await load(o.orderNo);
    submitted.value = true;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    submitting.value = false;
  }
}

function gotoOrder() {
  uni.redirectTo({ url: `${ROUTES.order}?orderNo=${order.value?.orderNo}` });
}

onLoad((q) => {
  // 原因清单由后端给；失败不挡住页面（FALLBACK 兜底）
  api.afterSaleReasons().then((r) => {
    if (r?.length) REASONS.value = r;
  }).catch(() => undefined);
  const no = (q?.orderNo as string) || "";
  if (no) load(no);
});
</script>

<template>
  <sh-scaffold v-if="order" title-key="afterSale.title">
    <!-- 已提交：展示进度 -->
    <template v-if="submitted">
      <view class="sh-card done">
        <text class="done__icon">✓</text>
        <text class="done__title">
          {{ order.status === "REFUNDED" ? $t("afterSale.refunded") : $t("afterSale.applied") }}
        </text>
        <text class="done__hint">
          {{ order.status === "REFUNDED" ? $t("afterSale.refundedHint") : $t("afterSale.appliedHint") }}
        </text>
      </view>

      <view class="sh-card block">
        <view v-for="(n, i) in order.timeline.slice(-3)" :key="i" class="node">
          <view class="node__dot" :class="{ 'is-last': i === order.timeline.slice(-3).length - 1 }" />
          <text class="node__label">{{ n.label }}</text>
        </view>
      </view>

      <view class="sh-btn block" @tap="gotoOrder">{{ $t("pay.viewOrder") }}</view>
    </template>

    <!-- 申请表单 -->
    <template v-else>
      <view class="sh-card">
        <biz-sku-row
          v-for="(it, i) in order.items.filter((x) => !x.isGift)"
          :key="i"
          :cover="it.cover"
          :title="it.title"
          :spec="it.spec"
        >
          <template #right>
            <text class="row__price sh-num">{{ money(it.price) }}</text>
          </template>
        </biz-sku-row>
      </view>

      <view class="sh-card block">
        <text class="sh-h2">{{ $t("afterSale.pickType") }}</text>
        <view class="types">
          <sh-option
            v-for="tp in TYPES"
            :key="tp"
            class="type"
            :selected="type === tp"
            @tap="type = tp"
          >
            <text class="type__t">{{ typeText(tp) }}</text>
            <text class="type__d">{{ typeText(tp, "Desc") }}</text>
          </sh-option>
        </view>
      </view>

      <view class="sh-card block">
        <text class="sh-h2">{{ $t("afterSale.pickReason") }}</text>
        <view class="reasons">
          <view
            v-for="r in REASONS"
            :key="r"
            class="sh-seg"
            :class="{ 'sh-seg--on': reason === r }"
            @tap="reason = r"
          >
            {{ $t(`afterSale.reason.${r}`) }}
          </view>
        </view>
      </view>

      <view class="sh-card block">
        <text class="sh-h2">{{ $t("afterSale.detail") }}</text>
        <textarea
          v-model="detail"
          class="ta"
          :placeholder="$t('afterSale.detailPh')"
          maxlength="200"
        />

        <text class="sh-muted imglabel">{{ $t("afterSale.images") }}</text>
        <sh-uploader class="imgs" :list="images" :max="3" :w="160" @add="pickImages"></sh-uploader>
      </view>

      <view v-if="instantRefund" class="sh-card block notice">
        <text class="notice__text">{{ $t("afterSale.instant") }}</text>
      </view>

      <sh-actionbar :pad="180">
        <view class="sh-btn" :class="{ 'is-disabled': !canSubmit }" @tap="submit">
          {{ submitting ? $t("confirm.submitting") : $t("afterSale.submit") }}
        </view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.block {
  margin-top: 20rpx;
}
.row__price {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
  flex-shrink: 0;
}
.types {
  display: flex;
  gap: 16rpx;
  margin-top: 24rpx;
}
/* 描边 + 说明文字那一档由 sh-option 给，这里只管等分 */
.type {
  flex: 1;
}
.type__t {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.type__d {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.5;
  margin-top: 8rpx;
}
.reasons {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 24rpx;
}
.ta {
  width: 100%;
  box-sizing: border-box;
  min-height: 160rpx;
  background: var(--sh-faint);
  border-radius: 24rpx;
  padding: 24rpx;
  font-size: 26rpx;
  color: var(--sh-ink);
  margin-top: 20rpx;
}
.imglabel {
  display: block;
  margin-top: 28rpx;
}
/* 只留这一段与页面版面有关的外边距 —— 格子本身（尺寸 / 圆角 / 底色 / 「＋」）
   全在 `sh-uploader` 里。两页此前的 `.img` 一族**逐字节相同**：
   160rpx 方格、24rpx 圆角、faint 底、48rpx 的 `＋` 字符。
   顺带把那个 `＋` 换成真图标 —— 字符跟着字体走，三端字形不一样。 */
.imgs {
  margin-top: 16rpx;
}
.notice {
  background: var(--sh-primary-tint);
}
.notice__text {
  font-size: 24rpx;
  color: var(--sh-primary-text);
  line-height: 1.6;
}
.done {
  text-align: center;
  padding-top: 48rpx;
  padding-bottom: 40rpx;
}
.done__icon {
  display: block;
  width: 96rpx;
  height: 96rpx;
  line-height: 96rpx;
  margin: 0 auto;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 48rpx;
  font-weight: 700;
}
.done__title {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-ink);
  margin-top: 24rpx;
}
.done__hint {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
  margin-top: 14rpx;
}
.node {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 12rpx 0;
}
.node__dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 9999px;
  background: var(--sh-line);
  flex-shrink: 0;
}
.node__dot.is-last {
  background: var(--sh-primary);
}
.node__label {
  font-size: 24rpx;
  color: var(--sh-ink);
}
.is-disabled {
  opacity: 0.45;
}
</style>
