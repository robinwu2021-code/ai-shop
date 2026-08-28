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
      <view class="stars sh-row">
        <text
          v-for="i in 5"
          :key="i"
          class="star"
          :class="{ 'is-on': i <= rating }"
          @tap="setRating(i)"
        >
          ★
        </text>
        <text class="txt-caption stars__label">{{ $t(`review.star${rating}`) }}</text>
      </view>

      <!-- 三维度：不强制，动了才算细评。只看总分的商家永远不知道
           「东西没问题，是送得太慢」——而那正是他能改的部分 -->
      <view v-for="d in DIMS" :key="d.key" class="dim sh-row sh-row--between">
        <text class="txt-sub">{{ $t(d.labelKey) }}</text>
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
        class="txt-sub ta"
        :placeholder="$t('review.contentPh')"
        maxlength="300"
      />
      <text class="txt-caption counter sh-num">{{ content.length }}/300</text>

      <text class="sh-muted imglabel">{{ $t("review.images") }}</text>
      <sh-uploader class="imgs" :list="images" :max="3" :w="160" @add="pickImages"></sh-uploader>
    </view>

    <sh-actionbar class="bar-center" :pad="220">
      <view class="sh-btn" :class="{ 'is-disabled': !canSubmit }" @tap="submit">
        {{ submitting ? $t("confirm.submitting") : $t("review.submit") }}
      </view>
      <text class="sh-hint sh-mt-sm">{{ $t("review.tip") }}</text>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
/* 条里除了按钮还有一行说明/取消，居中对齐 —— 定位归 `sh-actionbar`，
   这一条是这一页自己的排布。收编时它一度被连着定位一起删掉了。 */
.bar-center {
  text-align: center;
}

.dim {
  margin-top: 16rpx;
}

.dim__stars {
  display: flex;
  gap: 8rpx;
}
.star--sm {
  font-size: 34rpx;
}

.stars {
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
  /* 星标点亮色，不是告警色 —— 见 base.css 的 --sh-star */
  color: var(--sh-star);
}
.stars__label {
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
  color: var(--sh-ink);
}
.counter {
  display: block;
  text-align: end;
  margin-top: 8rpx;
}
.imglabel {
  display: block;
  margin-top: 24rpx;
}
/* 只留这一段与页面版面有关的外边距 —— 格子本身（尺寸 / 圆角 / 底色 / 「＋」）
   全在 `sh-uploader` 里。两页此前的 `.img` 一族**逐字节相同**：
   160rpx 方格、24rpx 圆角、faint 底、48rpx 的 `＋` 字符。
   顺带把那个 `＋` 换成真图标 —— 字符跟着字体走，三端字形不一样。 */
.imgs {
  margin-top: 16rpx;
}
</style>
