<script setup lang="ts">
// 发表评价。
// 评价一落库就进入商家评分计算，所以后端会校验「订单已完成 + 未评价过」——
// 前端这里只做体验，不承担校验责任（前端校验绕得过去，评分是能刷的东西）。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { chooseImages } from "@shared/ports/media";
import { ROUTES } from "@shared/utils/constants";
import type { Order, ReviewScores } from "@shared/types";

const { t } = useI18n();

const order = ref<Order | null>(null);
const goodsNo = ref("");
const rating = ref(5);
/**
 * 三维度（B-9.3）。默认跟随总分 —— 大多数人只想点一次星就走，
 * 强制细评会把评价率压下去；细评的人才是给商家真正可行动信息的人。
 */
const DIMS = [
  { key: "goods", labelKey: "review.dimGoods" },
  { key: "fulfillment", labelKey: "review.dimFulfill" },
  { key: "service", labelKey: "review.dimService" },
] as const;
const scores = ref<ReviewScores>({ goods: 5, fulfillment: 5, service: 5 });
/** 还没动过维度分时跟着总分走；动过就不再覆盖（用户的细评优先） */
const dimTouched = ref(false);
function setRating(v: number) {
  rating.value = v;
  if (!dimTouched.value) scores.value = { goods: v, fulfillment: v, service: v };
}
function setDim(key: keyof ReviewScores, v: number) {
  dimTouched.value = true;
  scores.value = { ...scores.value, [key]: v };
}
const content = ref("");
const images = ref<string[]>([]);
const submitting = ref(false);

/** 只评实付商品，赠品不参与 */
const target = computed(() =>
  order.value?.items.find((it) => !it.isGift && (!goodsNo.value || it.goodsNo === goodsNo.value)),
);

const canSubmit = computed(() => !!target.value && content.value.trim().length >= 5 && !submitting.value);

async function load(orderNo: string) {
  order.value = await api.orderDetail(orderNo);
  goodsNo.value = order.value.items.find((it) => !it.isGift)?.goodsNo ?? "";
}

async function pickImages() {
  try {
    const paths = await chooseImages(3);
    images.value = [...images.value, ...paths].slice(0, 3);
  } catch {
    // 取消，不提示
  }
}

async function submit() {
  const o = order.value;
  const it = target.value;
  if (!o || !it || !canSubmit.value) return;
  submitting.value = true;
  try {
    await api.createReview({
      orderNo: o.orderNo,
      goodsNo: it.goodsNo,
      rating: rating.value,
      content: content.value.trim(),
      images: images.value,
      scores: scores.value,
    });
    uni.showToast({ title: String(t("review.thanks")), icon: "none" });
    setTimeout(() => uni.redirectTo({ url: `${ROUTES.goods}?goodsNo=${it.goodsNo}` }), 800);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    submitting.value = false;
  }
}

onLoad((q) => {
  const no = (q?.orderNo as string) || "";
  goodsNo.value = (q?.goodsNo as string) || "";
  if (no) load(no);
});
</script>

<template>
  <sh-scaffold v-if="target" title-key="review.writeTitle">
    <view class="sh-card">
      <biz-sku-row
        :cover="target.cover"
        :title="target.title"
        :spec="target.spec"
      ></biz-sku-row>

      <!-- 星级：默认 5 星。默认 0 星会让人以为「必须选」，多一步操作 -->
      <view class="stars">
        <text
          v-for="i in 5"
          :key="i"
          class="star"
          :class="{ 'is-on': i <= rating }"
          @tap="setRating(i)"
        >
          ★
        </text>
        <text class="stars__label">{{ $t(`review.star${rating}`) }}</text>
      </view>

      <!-- 三维度：不强制，动了才算细评。只看总分的商家永远不知道
           「东西没问题，是送得太慢」——而那正是他能改的部分 -->
      <view v-for="d in DIMS" :key="d.key" class="dim">
        <text class="dim__label">{{ $t(d.labelKey) }}</text>
        <view class="dim__stars">
          <text
            v-for="i in 5"
            :key="i"
            class="star star--sm"
            :class="{ 'is-on': i <= scores[d.key] }"
            @tap="setDim(d.key, i)"
          >
            ★
          </text>
        </view>
      </view>
    </view>

    <view class="sh-card block">
      <textarea
        v-model="content"
        class="ta"
        :placeholder="$t('review.contentPh')"
        maxlength="300"
      />
      <text class="counter sh-num">{{ content.length }}/300</text>

      <text class="sh-muted imglabel">{{ $t("review.images") }}</text>
      <view class="imgs">
        <view v-for="(img, i) in images" :key="i" class="img">
          <image class="img__i" :src="img" mode="aspectFill" />
        </view>
        <view v-if="images.length < 3" class="img img--add" @tap="pickImages">
          <text class="img__plus">＋</text>
        </view>
      </view>
    </view>

    <view class="actionbar">
      <view class="sh-btn" :class="{ 'is-disabled': !canSubmit }" @tap="submit">
        {{ submitting ? $t("confirm.submitting") : $t("review.submit") }}
      </view>
      <text class="tip">{{ $t("review.tip") }}</text>
    </view>
    <view class="spacer" />
  </sh-scaffold>
</template>

<style scoped>
.dim {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 18rpx;
}
.dim__label {
  font-size: 26rpx;
  color: var(--sh-sub);
}
.dim__stars {
  display: flex;
  gap: 8rpx;
}
.star--sm {
  font-size: 34rpx;
}

.stars {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 32rpx;
  direction: ltr;
}
.star {
  font-size: 48rpx;
  color: var(--sh-line);
  line-height: 1;
}
.star.is-on {
  color: var(--sh-warning);
}
.stars__label {
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-inline-start: 12rpx;
}
.block {
  margin-top: 20rpx;
}
.ta {
  width: 100%;
  box-sizing: border-box;
  min-height: 220rpx;
  background: var(--sh-faint);
  border-radius: 24rpx;
  padding: 24rpx;
  font-size: 26rpx;
  color: var(--sh-ink);
}
.counter {
  display: block;
  text-align: end;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 10rpx;
}
.imglabel {
  display: block;
  margin-top: 24rpx;
}
.imgs {
  display: flex;
  gap: 16rpx;
  margin-top: 16rpx;
}
.img {
  width: 160rpx;
  height: 160rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  overflow: hidden;
}
.img__i {
  width: 100%;
  height: 100%;
}
.img--add {
  display: flex;
  align-items: center;
  justify-content: center;
}
.img__plus {
  font-size: 48rpx;
  color: var(--sh-sub);
  line-height: 1;
}
.actionbar {
  position: fixed;
  inset-inline: 28rpx;
  bottom: calc(28rpx + env(safe-area-inset-bottom));
  text-align: center;
}
.tip {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 18rpx;
}
.is-disabled {
  opacity: 0.45;
}
.spacer {
  height: 220rpx;
}
</style>
