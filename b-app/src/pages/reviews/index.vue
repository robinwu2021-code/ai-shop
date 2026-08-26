<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
// 评价与回复（B-11.7）。
//
// 排序：**未回复的排前面**。商家进来是为了「把该回的回掉」，不是为了翻阅历史 ——
// 按时间倒序会让三条未回复的差评沉在十条好评下面。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { datetime } from "@shared/utils/datetime";
import { REVIEW_RULES } from "@shared/utils/constants";
import type { Review } from "@shared/types";

const { t } = useI18n();

const list = ref<Review[]>([]);
const replying = ref("");
/** 正在申诉的评价号 —— 展开理由输入框 */
const appealing = ref("");
const appealText = ref("");
const text = ref("");
const busy = ref(false);

const sorted = computed(() =>
  [...list.value].sort((a, b) => {
    const ar = a.reply ? 1 : 0;
    const br = b.reply ? 1 : 0;
    if (ar !== br) return ar - br; // 未回复优先
    return b.createdAt - a.createdAt;
  }),
);

const pending = computed(() => list.value.filter((r) => !r.reply).length);

async function load() {
  replying.value = "";
  text.value = "";
  list.value = await api.mReviewList();
}

/**
 * **回复只有一次**，所以这里不回填旧文案 —— 回填等于暗示「可以改」。
 *
 * 后端 `reply()` 明确拒绝第二次（CONFLICT，理由是「回复是公开表态，
 * 反复改会变成评论区里来回改口」）。而这一页原先给已回复的评价挂了个
 * 「修改回复」，点进去还把原文填好，写完发出去只得到一句
 * **「资源冲突，请刷新后重试」** —— 刷新一百次也一样，那条路本来就不通。
 */
function startReply(r: Review) {
  replying.value = r.reviewNo;
  text.value = "";
}

async function submit(r: Review) {
  if (!text.value.trim() || busy.value) return;
  busy.value = true;
  try {
    await api.mReplyReview(r.reviewNo, text.value.trim());
    uni.showToast({ title: t("reviews.replied"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/**
 * 申诉差评（B-9.4）。**只有低分能申诉**，规则在契约层强制，这里只做入口可见性 ——
 * 按钮都不显示，比点了才报错好。
 */
const canAppeal = (r: Review) => r.rating <= REVIEW_RULES.appealMaxRating && !r.appeal;

async function submitAppeal(r: Review) {
  if (!appealText.value.trim()) {
    uni.showToast({ title: t("reviews.appealNeedReason"), icon: "none" });
    return;
  }
  try {
    await api.mAppealReview(r.reviewNo, appealText.value.trim());
    appealing.value = "";
    appealText.value = "";
    uni.showToast({ title: t("reviews.appealed"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="reviews.title" :denied="!merchant.can('biz:review')">
    <view class="head">
      <text class="sh-h1">{{ $t("reviews.title") }}</text>
      <text v-if="pending" class="sh-chip sh-chip--warning">
        {{ $t("reviews.pending", { n: pending }) }}
      </text>
    </view>

    <sh-empty v-if="!list.length" :text='$t("reviews.empty")'></sh-empty>

    <view v-for="r in sorted" :key="r.reviewNo" class="sh-card item">
      <view class="item__head">
        <text class="item__who">{{ r.avatar }} {{ r.nickname }}</text>
        <!-- single-review：这是**某个人给的星数**，不是聚合评分，不需要 ratingCount 护栏 -->
        <sh-rating :value="r.rating" :size="24"></sh-rating>
      </view>
      <text class="sh-muted item__meta">{{ r.spec }} · {{ datetime(r.createdAt) }}</text>

      <!-- 三维度（B-9.3）：只看总分看不出「货好但送得慢」，而那正是能改的部分。
           老评价没有维度分，不显示这一行而不是显示三个 0 -->
      <view v-if="r.scores" class="dims">
        <text class="dims__i">{{ $t("reviews.dimGoods") }} {{ r.scores.goods }}</text>
        <text class="dims__i">{{ $t("reviews.dimFulfill") }} {{ r.scores.fulfillment }}</text>
        <text class="dims__i">{{ $t("reviews.dimService") }} {{ r.scores.service }}</text>
      </view>
      <text class="item__content">{{ r.content }}</text>
      <view v-if="r.images.length" class="imgs">
        <text v-for="(img, i) in r.images" :key="i" class="imgs__i">{{ img }}</text>
      </view>

      <view v-if="r.reply && replying !== r.reviewNo" class="reply">
        <text class="reply__label">{{ $t("reviews.myReply") }}</text>
        <text>{{ r.reply }}</text>
      </view>

      <template v-if="replying === r.reviewNo">
        <textarea
          v-model="text"
          class="field__area"
          :placeholder="$t('reviews.replyPh')"
          maxlength="100"
        />
        <!-- 「只能发一次」要在他动笔之前说，不是发完之后用一句报错告诉他 -->
        <text class="sh-muted once">{{ $t("reviews.replyOnce") }}</text>
        <view class="btns">
          <text class="btn btn--ghost" @tap="replying = ''">{{ $t("common.cancel") }}</text>
          <text class="btn" @tap="submit(r)">{{ $t("reviews.submit") }}</text>
        </view>
      </template>
      <!-- 申诉状态：提交后商家能看到进度与裁决说明。
           平台端 P-13.1 的裁决台早就建好了，此前缺的正是这个入口 —— 台子一直空转 -->
      <view v-if="r.appeal" class="appeal" :class="`is-${r.appeal.status}`">
        <text class="appeal__label">{{ $t(`reviews.appeal${r.appeal.status}`) }}</text>
        <text class="sh-muted">{{ r.appeal.verdict || $t("reviews.appealWaiting") }}</text>
      </view>

      <template v-if="appealing === r.reviewNo">
        <textarea
          v-model="appealText"
          class="field__area"
          :placeholder="$t('reviews.appealPh')"
          maxlength="120"
        />
        <view class="btns">
          <text class="btn btn--ghost" @tap="appealing = ''">{{ $t("common.cancel") }}</text>
          <text class="btn" @tap="submitAppeal(r)">{{ $t("reviews.appealSubmit") }}</text>
        </view>
      </template>

      <view v-else-if="replying !== r.reviewNo" class="acts">
        <!-- 已回复的不再给入口：那条路后端是关着的（见 startReply 的注释）。
             上面的「我的回复」块已经把回复内容显示出来了，这里不需要再有按钮 -->
        <text v-if="!r.reply" class="sh-link" @tap="startReply(r)">
          {{ $t("reviews.reply") }}
        </text>
        <text v-if="canAppeal(r)" class="sh-link sh-link--warn" @tap="appealing = r.reviewNo">
          {{ $t("reviews.appeal") }}
        </text>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.dims {
  display: flex;
  gap: 16rpx;
  margin-top: 10rpx;
}
.dims__i {
  font-size: 24rpx;
  color: var(--sh-sub);
  background: var(--sh-faint);
  padding: 4rpx 12rpx;
  border-radius: 9999px;
}
.appeal {
  margin-top: 14rpx;
  padding: 16rpx 20rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
}
.appeal.is-UPHELD {
  background: var(--sh-success-tint);
}
.appeal.is-REJECTED {
  background: var(--sh-danger-tint);
}
.appeal__label {
  display: block;
  font-size: 24rpx;
  font-weight: 600;
  color: var(--sh-ink);
  margin-bottom: 6rpx;
}
.acts {
  display: flex;
  gap: 28rpx;
  margin-top: 16rpx;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.item {
  margin-top: 14rpx;
}
.item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.item__who {
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.item__meta {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
}
.item__content {
  display: block;
  margin-top: 16rpx;
  font-size: 28rpx;
  line-height: 1.6;
  color: var(--sh-ink);
}
.imgs {
  display: flex;
  gap: 12rpx;
  margin-top: 16rpx;
}
.imgs__i {
  width: 110rpx;
  height: 110rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 48rpx;
  text-align: center;
  line-height: 110rpx;
}
.reply {
  margin-top: 20rpx;
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 28rpx;
  color: var(--sh-ink);
  line-height: 1.6;
}
.reply__label {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-bottom: 6rpx;
}
.once {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  line-height: 1.5;
}
.btns {
  display: flex;
  gap: 16rpx;
  margin-top: 20rpx;
}
.btn {
  flex: 1;
  text-align: center;
  padding: 22rpx 0;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 28rpx;
  font-weight: 600;
}
.btn--ghost {
  background: var(--sh-faint);
  color: var(--sh-sub);
}
.sh-link {
  display: inline-block;
  margin-top: 20rpx;
}
</style>
