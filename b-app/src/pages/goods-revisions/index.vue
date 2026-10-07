<script setup lang="ts">
/**
 * 提交历史（TDD-商品编辑页-录入落点与发布历史 AC11）。
 *
 * <p><b>为什么要这一页</b>：发布此前不留痕 —— `prd_goods_draft` 只存当前一份草稿，
 * 发布就把行删掉。于是发完只剩「线上是什么」，查不到「发过什么」：谁在什么时候
 * 改了哪几项、上一版长什么样、为什么被驳回，一概无从回答。发布确认页能说出
 * 「线上在你保存之后有过变动」，却说不出**是谁改的**。
 *
 * <p><b>这一页最容易出的错是把「最新」当成「线上在售」</b> —— 它们常常不是同一版
 * （存了草稿还没发布时，最新那版买家看不到）。所以顶部单独一行写「线上在售 vN」，
 * 不让人从列表里自己推。
 *
 * <p>整卡可点进详情看两份差异（AC12）。卡里不放按钮 —— 列表卡的约定。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { GoodsEntrySource, GoodsRevision, GoodsRevisionStatus } from "@/api/contract";
import { ROUTES } from "@/shared/nav";
import { onlineRevisionOf, statusChipOf } from "./revisions";

const { t } = useI18n();
const merchant = useMerchantStore();

const goodsNo = ref("");
const rows = ref<GoodsRevision[]>([]);
const loading = ref(true);
/** 这次没取到。**与「这儿本来就没有」是两件事** —— 空列表与拉失败不能长一样 */
const failed = ref(false);

/** 线上在售那一版。判断在 `./revisions.ts`（纯函数、有测试）—— 见那里的类注释 */
const onlineNo = computed(() => onlineRevisionOf(rows.value));

/* 状态与来源都是后端枚举，键用字面量拼一次 —— 动态键会让整片词条不受 i18n 闸门管 */
const STATUS_LABEL: Record<GoodsRevisionStatus, string> = {
  DRAFT: "goods.revStatusDRAFT",
  ONLINE: "goods.revStatusONLINE",
  SUPERSEDED: "goods.revStatusSUPERSEDED",
  REJECTED: "goods.revStatusREJECTED",
};
const SRC_LABEL: Record<GoodsEntrySource, string> = {
  MANUAL: "goods.revSrcMANUAL",
  QUICK_TEXT: "goods.revSrcQUICK_TEXT",
  ZIP: "goods.revSrcZIP",
  IMAGE: "goods.revSrcIMAGE",
};


/** 点一版进详情看两份差异（AC12）。整卡可点 —— 卡里不放按钮 */
function toDetail(revisionNo: number) {
  uni.navigateTo({ url: `${ROUTES.goodsRevision}?goodsNo=${goodsNo.value}&revisionNo=${revisionNo}` });
}

async function load() {
  loading.value = true;
  try {
    rows.value = await api.mGoodsRevisions(goodsNo.value);
    failed.value = false;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

onLoad((q) => {
  if (!q?.goodsNo) {
    uni.navigateBack();
    return;
  }
  goodsNo.value = q.goodsNo;
  void load();
});
</script>

<template>
  <!-- 与编辑/发布同一道门（biz:goods）：店员进不来 -->
  <sh-scaffold
    title-key="goods.revTitle"
    :denied="!merchant.can('biz:goods')"
    :failed="failed"
    @retry="load"
  >
    <view v-if="loading" class="sh-card">
      <text class="txt-sub">{{ $t("common.loading") }}</text>
    </view>

    <template v-else-if="rows.length">
      <view class="sh-card">
        <text class="txt-strong">{{ $t("goods.revCount", { n: rows.length }) }}</text>
        <!--
          「线上在售 vN」单独一行，不让人从列表里推 ——
          最新那一版常常还没发布，买家看到的是另一版。
        -->
        <text class="txt-caption sh-muted rev__live">
          {{ onlineNo === null ? $t("goods.revOnlineNone") : $t("goods.revOnlineNow", { n: onlineNo }) }}
        </text>
      </view>

      <view v-for="r in rows" :key="r.revisionNo" class="sh-card" @tap="toDetail(r.revisionNo)">
        <view class="sh-row sh-row--between">
          <text class="txt-strong sh-num">v{{ r.revisionNo }}</text>
          <text class="sh-chip" :class="statusChipOf(r.status)">{{ $t(STATUS_LABEL[r.status]) }}</text>
        </view>
        <text class="txt-caption sh-muted rev__line">
          {{ $t("goods.revSaved", { t: r.savedAt ?? "—", by: r.savedBy ?? "—" }) }}
          · {{ $t(SRC_LABEL[r.entrySource]) }}
        </text>
        <text v-if="r.publishedAt" class="txt-caption sh-muted rev__line">
          {{ $t("goods.revPublished", { t: r.publishedAt, by: r.publishedBy ?? "—" }) }}
        </text>
        <text class="txt-caption rev__line">
          {{ r.changeSummary
            ? $t("goods.revChanged", { s: r.changeSummary })
            : $t("goods.revChangedNone") }}
        </text>
        <!-- 驳回原因是商家最需要的一行，用警示色，不混在灰字里 -->
        <text v-if="r.rejectReason" class="txt-caption is-warning rev__line">{{ r.rejectReason }}</text>
      </view>
    </template>

    <view v-else class="sh-card">
      <text class="txt-sub">{{ $t("goods.revEmpty") }}</text>
    </view>
  </sh-scaffold>
</template>

<style scoped>
/* 块间距由外壳给（.sh-scaffold > * + *），顶层块不写纵向 margin */
.rev__live {
  display: block;
  margin-top: 8rpx;
}
.rev__line {
  display: block;
  margin-top: 8rpx;
}
</style>
