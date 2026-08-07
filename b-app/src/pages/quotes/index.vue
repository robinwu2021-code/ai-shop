<script setup lang="ts">
// 邻里求团报价（B-11.6.3~6.5）。
//
// 这是平台区别于社区团购的那条线：需求先于供给，邻居先发「想买床垫」，商家再来报价。
//
// 三条硬规则（ADR-003）：
//   1. **不做事前审核** —— 审核是抽样的、拖慢报价速度，而锁价是必然的
//   2. **锁价**：被选定的瞬间价格写入快照，此后改不了（这里表现为已锁价不能再改）
//   3. **改价留痕，只公示涨价** —— 降价对邻居是好事，公示反而劝退商家降价
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money, toMinor } from "@shared/utils/money";
import type { GroupRequest, Quote } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const list = ref<GroupRequest[]>([]);
const editing = ref("");
const form = ref({ price: "", minCount: "", desc: "" });
const busy = ref(false);

/** 我在这单里的报价（有则是改价，无则是首次报价） */
function myQuote(r: GroupRequest): Quote | undefined {
  return r.quotes.find((q) => q.merchant.merchantNo === merchant.profile?.merchantNo);
}

const canQuote = computed(() => merchant.isActive);

async function load() {
  editing.value = "";
  list.value = await api.mRequestList();
}

function start(r: GroupRequest) {
  const mine = myQuote(r);
  editing.value = r.requestNo;
  form.value = {
    price: mine ? String(mine.priceMinor / 100) : "",
    minCount: mine ? String(mine.minCount) : String(r.expectQty),
    desc: mine?.desc ?? "",
  };
}

async function submit(r: GroupRequest) {
  if (!form.value.price || !form.value.minCount) {
    uni.showToast({ title: t("quotes.required"), icon: "none" });
    return;
  }
  if (busy.value) return;
  busy.value = true;
  try {
    await api.mQuote(r.requestNo, {
      priceMinor: toMinor(form.value.price),
      minCount: Number(form.value.minCount),
      desc: form.value.desc.trim(),
    });
    uni.showToast({ title: t("quotes.submitted"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="quotes.title">
    <text class="sh-h1">{{ $t("quotes.title") }}</text>
    <text class="sh-muted intro">{{ $t("quotes.intro") }}</text>

    <sh-empty v-if="!list.length" :text='$t("quotes.empty")'></sh-empty>

    <view v-for="r in list" :key="r.requestNo" class="sh-card item">
      <view class="item__head">
        <text class="item__title">{{ r.title }}</text>
        <text class="sh-chip sh-chip--primary">{{ $t("quotes.wanted", { n: r.interestedCount }) }}</text>
      </view>
      <text class="sh-muted item__desc">{{ r.desc }}</text>

      <view class="meta">
        <text class="sh-muted">{{ $t("quotes.expectQty") }} {{ r.expectQty }}</text>
        <text v-if="r.budgetMinor" class="sh-muted sh-num">
          {{ $t("quotes.budget") }} {{ money(r.budgetMinor) }}
        </text>
        <text class="sh-muted">{{ r.pickupName }}</text>
      </view>

      <!-- 已有报价：从低到高排，商家看得到自己排第几 -->
      <view v-if="r.quotes.length" class="quotes">
        <view
          v-for="q in r.quotes"
          :key="q.quoteNo"
          class="quote"
          :class="{ 'is-mine': q.merchant.merchantNo === merchant.profile?.merchantNo }"
        >
          <view class="quote__l">
            <text class="quote__name">
              {{ q.merchant.logo }} {{ q.merchant.name }}
              <text v-if="q.merchant.merchantNo === merchant.profile?.merchantNo" class="mine-tag">
                {{ $t("quotes.mine") }}
              </text>
            </text>
            <text class="sh-muted">{{ $t("quotes.minCount") }} {{ q.minCount }} · {{ q.desc || "—" }}</text>
            <!-- 只公示涨价：曾报 ¥X -->
            <text v-if="q.revisions.length" class="raised sh-num">
              {{ $t("quotes.raised", { p: money(q.revisions[q.revisions.length - 1]!.priceMinor) }) }}
            </text>
            <text v-if="q.merchant.breachCount" class="breach">
              {{ $t("quotes.breach", { n: q.merchant.breachCount }) }}
            </text>
          </view>
          <view class="quote__r">
            <text class="quote__p sh-num">{{ money(q.priceMinor) }}</text>
            <text v-if="q.locked" class="sh-chip">{{ $t("quotes.locked") }}</text>
          </view>
        </view>
      </view>

      <template v-if="editing === r.requestNo">
        <view class="field">
          <text class="field__label">{{ $t("quotes.price") }}</text>
          <input v-model="form.price" class="field__input sh-num" type="digit" />
        </view>
        <view class="field">
          <text class="field__label">{{ $t("quotes.minCount") }}</text>
          <input v-model="form.minCount" class="field__input sh-num" type="number" />
        </view>
        <view class="field">
          <text class="field__label">{{ $t("quotes.desc") }}</text>
          <input v-model="form.desc" class="field__input" :placeholder="$t('quotes.descPh')" />
        </view>
        <view class="btns">
          <text class="btn btn--ghost" @tap="editing = ''">{{ $t("common.cancel") }}</text>
          <text class="btn" @tap="submit(r)">{{ $t("quotes.submit") }}</text>
        </view>
        <text class="tip">{{ $t("quotes.lockHint") }}</text>
      </template>

      <view v-else-if="canQuote" class="sh-btn sh-btn--soft act" @tap="start(r)">
        {{ myQuote(r) ? $t("quotes.change") : $t("quotes.doQuote") }}
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.intro {
  display: block;
  margin: 12rpx 8rpx 0;
  line-height: 1.6;
}
.item {
  margin-top: 14rpx;
}
.item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}
.item__title {
  flex: 1;
  font-size: 30rpx;
  font-weight: 400;
  color: var(--sh-ink);
}
.item__desc {
  display: block;
  margin-top: 10rpx;
  line-height: 1.6;
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
  margin-top: 16rpx;
}
.quotes {
  margin-top: 24rpx;
}
.quote {
  display: flex;
  align-items: flex-start;
  gap: 20rpx;
  padding: 20rpx 0;
}
.quote.is-mine {
  background: var(--sh-primary-tint);
  border-radius: 24rpx;
  padding: 20rpx 24rpx;
}
.quote__l {
  flex: 1;
  min-width: 0;
}
.quote__name {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.mine-tag {
  font-size: 24rpx;
  color: var(--sh-primary);
}
.raised {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: var(--sh-warning);
}
.breach {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
  color: var(--sh-danger);
}
.quote__r {
  text-align: end;
}
.quote__p {
  display: block;
  font-size: 30rpx;
  font-weight: 400;
  color: var(--sh-ink);
}
.btns {
  display: flex;
  gap: 16rpx;
  margin-top: 24rpx;
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
.act {
  margin-top: 24rpx;
}
.tip {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
