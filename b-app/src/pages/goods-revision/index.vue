<script setup lang="ts">
/**
 * 某一版的变更详情（TDD-商品编辑页-录入落点与发布历史 AC12）。
 *
 * <p><b>两份差异，答两个不同的问题</b>：「这一版当年改了什么」（对比它的基版）
 * 用于追溯 —— 三个月后回头问「这个价为什么是 12.8」，答案在这儿；
 * 「跟此刻线上的差别」用于决定要不要取回。只给一份的话，商家没法判断
 * 取回会动到哪些东西。
 *
 * <p>排版照 `goods-publish` 的差异段（同一个 `DiffRow`、同一种左旧右新）——
 * 同一种信息两种形状的话，商家要学两遍。
 */
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { GoodsRevision } from "@/api/contract";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const goodsNo = ref("");
const revisionNo = ref(0);
const rev = ref<GoodsRevision | null>(null);
const loading = ref(true);
const failed = ref(false);
const forking = ref(false);

async function load() {
  loading.value = true;
  try {
    rev.value = await api.mGoodsRevision(goodsNo.value, revisionNo.value);
    failed.value = false;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

/**
 * 取回这一版。**过一次确认** —— 它会把手上那份未发布草稿换掉
 * （后端守「至多一行未发布」），而那份草稿可能是他刚改了一半的东西。
 */
async function fork() {
  if (forking.value || !rev.value?.canFork) return;
  if (!(await confirm({ title: String(t("goods.revFork")), hint: String(t("goods.revForkHint")) }))) {
    return;
  }
  forking.value = true;
  try {
    const made = await api.mForkRevision(goodsNo.value, revisionNo.value);
    uni.showToast({ title: String(t("goods.revForkDone", { n: made.revisionNo })), icon: "none" });
    setTimeout(() => uni.navigateBack(), 1200);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    forking.value = false;
  }
}

onLoad((q) => {
  if (!q?.goodsNo || !q?.revisionNo) {
    uni.navigateBack();
    return;
  }
  goodsNo.value = q.goodsNo;
  revisionNo.value = Number(q.revisionNo) || 0;
  void load();
});
</script>

<template>
  <sh-scaffold
    title-key="goods.revTitle"
    :denied="!merchant.can('biz:goods')"
    :failed="failed"
    @retry="load"
  >
    <view v-if="loading" class="sh-card">
      <text class="txt-sub">{{ $t("common.loading") }}</text>
    </view>

    <template v-else-if="rev">
      <view class="sh-card">
        <text class="txt-strong">{{ $t("goods.revDetailTitle", { n: rev.revisionNo }) }}</text>
        <text class="txt-caption sh-muted rev__line">
          {{ $t("goods.revSaved", { t: rev.savedAt ?? "—", by: rev.savedBy ?? "—" }) }}
        </text>
        <text v-if="rev.publishedAt" class="txt-caption sh-muted rev__line">
          {{ $t("goods.revPublished", { t: rev.publishedAt, by: rev.publishedBy ?? "—" }) }}
        </text>
      </view>

      <view class="sh-card">
        <text class="txt-strong">{{ $t("goods.revFromPrev") }}</text>
        <text v-if="!rev.changesFromPrev?.length" class="txt-caption sh-muted rev__line">
          {{ $t("goods.revNoChange") }}
        </text>
        <view v-for="c in rev.changesFromPrev ?? []" :key="c.field" class="diff">
          <text class="txt-caption txt-quiet">{{ c.label }}</text>
          <view class="diff__vals sh-row sh-row--baseline">
            <text class="txt-sub sh-void">{{ c.before || "—" }}</text>
            <!-- sh-icon 而不是「→」字符：字符伪图标跟着系统字形走，RTL 也不会自己翻 -->
            <sh-icon name="chevronRight" :size="14" class="txt-quiet"></sh-icon>
            <text class="txt-sub">{{ c.after || "—" }}</text>
          </view>
        </view>
      </view>

      <view class="sh-card">
        <text class="txt-strong">{{ $t("goods.revVsOnline") }}</text>
        <text v-if="!rev.changesVsOnline?.length" class="txt-caption sh-muted rev__line">
          {{ $t("goods.revNoChange") }}
        </text>
        <view v-for="c in rev.changesVsOnline ?? []" :key="c.field" class="diff">
          <text class="txt-caption txt-quiet">{{ c.label }}</text>
          <view class="diff__vals sh-row sh-row--baseline">
            <text class="txt-sub sh-void">{{ c.before || "—" }}</text>
            <sh-icon name="chevronRight" :size="14" class="txt-quiet"></sh-icon>
            <text class="txt-sub">{{ c.after || "—" }}</text>
          </view>
        </view>
      </view>

      <!--
        取回只在能取回时出现(线上在售那一版取回毫无意义)。
        **按钮说的是「建草稿」不是「回滚」** —— 它不直接改线上。
      -->
      <template v-if="rev.canFork">
        <sh-actionbar pill="plain" dock>
          <view class="sh-btn sh-fill" :class="{ 'is-disabled': forking }" @tap="fork">
            {{ $t("goods.revFork") }}
          </view>
        </sh-actionbar>
        <text class="txt-caption sh-muted rev__hint">{{ $t("goods.revForkHint") }}</text>
      </template>
    </template>
  </sh-scaffold>
</template>

<style scoped>
/* 块间距由外壳给（.sh-scaffold > * + *），顶层块不写纵向 margin */
.rev__line {
  display: block;
  margin-top: 8rpx;
}
.rev__hint {
  display: block;
  text-align: center;
  padding: 8rpx 0;
}
.diff {
  padding: 16rpx 0;
  border-top: var(--sh-hairline-soft);
}
.diff__vals {
  margin-top: 8rpx;
}
</style>
