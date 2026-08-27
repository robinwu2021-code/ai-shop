<script setup lang="ts">
// 会员（P1）。**路由沿用 pages/customers** —— 它是「我的客户」那一页的升级版，
// 换路由只会让存量深链失效，而这一页本来就从「我的」进。
//
// 平台电商给商家看的是「流量、转化率、UV」；小店老板要的是另一种东西：
// **张阿姨上个月每周都来，这半个月没来了**。所以这一页只回答三个问题：
// 有多少人、谁快流失了、这个人是谁带来的。没有图表，没有漏斗。
//
// ⚠️ 隐私：只给手机号后四位（B12）。按号找人**必须输完整号** ——
// 前缀模糊查询会把会员库变成一本通讯录，输「138」就能翻出一屏人。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { FEATURES } from "@shared/utils/constants";
import { money } from "@shared/utils/money";
import type { Member, MemberStats, MemberTag } from "@shared/types";
import { pick, prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const stats = ref<MemberStats | null>(null);
const list = ref<Member[]>([]);
const loading = ref(false);

/** 四层之一，或空 = 全部。点顶部那四个数字就是在切它 */
const level = ref("");
/** 门店筛选。多店主体才出现；空 = 全部门店 */
const storeNo = ref("");
const phone = ref("");
/** 输了号但不足 11 位时给一句解释 —— 否则他会以为「这个人不见了」 */
const phonePartial = computed(() => phone.value.length > 0 && phone.value.length < 11);

/** 选中的标签。**取交集** —— 点第二个是想收窄，并集会让人数反而涨 */
const pickedTags = ref<string[]>([]);
const tags = ref<MemberTag[]>([]);

const LEVELS = ["NEW", "REGULAR", "LOYAL", "SLEEPING"] as const;

function countOf(lv: string) {
  const s = stats.value;
  if (!s) return 0;
  if (lv === "NEW") return s.newCount;
  if (lv === "REGULAR") return s.regularCount;
  if (lv === "LOYAL") return s.loyalCount;
  return s.sleepingCount;
}

async function load() {
  if (loading.value) return;
  loading.value = true;
  try {
    const [s, page] = await Promise.all([
      api.mMemberStats(storeNo.value || undefined),
      api.mMembers({
        storeNo: storeNo.value || undefined,
        level: level.value || undefined,
        tagNos: pickedTags.value.length ? pickedTags.value.join(",") : undefined,
        phone: phone.value.length >= 11 ? phone.value : undefined,
        page: 1,
        size: 50,
      }),
    ]);
    stats.value = s;
    list.value = page.records;
    if (!tags.value.length) {
      tags.value = (await api.mMemberTags().catch(() => []))
        .filter((x) => x.status === "ACTIVE");
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

function pickLevel(v: string) {
  level.value = level.value === v ? "" : v;
  void load();
}

function toggleTag(no: string) {
  pickedTags.value = pickedTags.value.includes(no)
    ? pickedTags.value.filter((x) => x !== no)
    : [...pickedTags.value, no];
  void load();
}

/**
 * 把**当前这组筛选条件**存成人群。
 *
 * 在筛出来的地方存，而不是另开一个筛选器 —— 少一处口径，也少一次「为什么两边人数不一样」。
 * 存的是条件不是名单：发券那一刻会重算，所以这里先把试算的两个数摆给他看。
 */
async function saveAsSegment() {
  const rule = {
    level: level.value || undefined,
    tagNos: pickedTags.value.length ? [...pickedTags.value] : undefined,
  };
  const pv = await api.mPreviewMemberSegment({
    scopeStoreNo: storeNo.value || undefined,
    rule,
  }).catch(() => null);
  if (!pv) return;
  /*
   * 试算结果现在走 `hint`（说明），不必再挤进标题 ——
   * 当初挤进标题是因为 `showModal` 的 `content` 在 `editable` 下是**初值**：
   * 放那儿的话商家按下确定就存出一个叫「命中 1 人…」的人群，
   * 而他并不觉得自己输了这行字。`prompt()` 把说明与初值拆成两个参数，
   * 这个坑靠签名就没了（另外两处一直错着的，见 prompt.ts）。
   * 两个数都报：线索会员与退订的人进不了受众，只报命中数他会以为发漏了。
   */
  const input = await prompt({
    title: String(t("members.saveSegment")),
    hint: String(t("members.segmentPreview", { n: pv.count, m: pv.reachable })),
    placeholder: String(t("members.segmentNamePh")),
  });
  const name = (input ?? "").trim();
  if (!name) return;
  try {
    await api.mSaveMemberSegment({ name, scopeStoreNo: storeNo.value || undefined, rule });
    uni.showToast({ title: t("members.segmentSaved"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

async function pickStore() {
  const stores = merchant.stores;
  const items = [String(t("members.allStores")), ...stores.map((s) => s.name || s.storeNo)];
  const idx = await pick({ items, selected: storeNo.value
    ? stores.findIndex((s) => s.storeNo === storeNo.value) + 1 : 0 });
  if (idx === null) return;
  storeNo.value = idx === 0 ? "" : stores[idx - 1]?.storeNo ?? "";
  void load();
}

function storeName(no: string) {
  return merchant.stores.find((s) => s.storeNo === no)?.name || no;
}

function go(url: string) {
  uni.navigateTo({ url });
}

function open(m: Member) {
  uni.navigateTo({ url: `/pages/member-detail/index?memberNo=${m.memberNo}` });
}

/** 沉睡用警示色：那是店主唯一能立刻行动的一批 */
function levelClass(lv?: string | null) {
  return lv === "SLEEPING" ? "sh-chip--warning" : "sh-chip--primary";
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="members.title" :denied="!merchant.can('biz:customer')">
    <!--
      顶部一行：门店切换（多店才有）+ 两个入口。
      录入与标签放在这里而不是详情里 —— 它们作用在**整份名单**上，不是某一个人身上。
    -->
    <view class="bar">
      <text v-if="merchant.multiStore" class="sh-chip sh-chip--primary" @tap="pickStore">
        {{ storeNo ? storeName(storeNo) : $t("members.allStores") }} ▾
      </text>
      <text class="sh-chip" @tap="go('/pages/member-tags/index')">{{ $t("memberTags.title") }}</text>
      <text class="sh-chip" @tap="go('/pages/member-segments/index')">
        {{ $t("memberSegments.title") }}
      </text>
      <!--
        群发入口按开关显示。**代码在包里，入口先不露出来** ——
        灰度对象没定之前不该让任何商家点得到它（见 FEATURES.memberReach）。
      -->
      <text
        v-if="FEATURES.memberReach"
        class="sh-chip"
        @tap="go('/pages/member-reach/index')"
      >
        {{ $t("reach.entry") }}
      </text>
      <text class="sh-chip sh-chip--primary" @tap="go('/pages/member-add/index')">
        ＋ {{ $t("memberAdd.title") }}
      </text>
    </view>

    <!-- 四层数字即入口：点一个就按那一层筛，再点一下取消 -->
    <sh-stat
      boxed
      :active="level"
      :items="LEVELS.map((lv) => ({
        key: lv,
        value: countOf(lv),
        label: String($t(`members.level.${lv}`)),
        tone: lv === 'SLEEPING' ? 'primary' : undefined,
      }))"
      @pick="pickLevel"
    ></sh-stat>

    <text v-if="stats" class="sh-muted sub">
      {{ $t("members.summary", { n: stats.newThisMonth, m: stats.reachable }) }}
    </text>

    <!--
      未计入的买家。**先说，比等他问强** ——
      商家一定会拿订单数与会员数对，对不上时他的第一反应是数据丢了。
    -->
    <view v-if="stats && stats.unlinkedBuyers > 0" class="notice">
      {{ $t("members.unlinked", { n: stats.unlinkedBuyers }) }}
    </view>

    <!--
      标签筛选。**取交集**：点第二个标签是想收窄。
      筛出来之后可以直接存成人群 —— 条件在哪儿筛就在哪儿存。
    -->
    <view v-if="tags.length" class="tagbar">
      <text
        v-for="tg in tags"
        :key="tg.tagNo"
        class="sh-chip"
        :class="{ 'sh-chip--primary': pickedTags.includes(tg.tagNo) }"
        @tap="toggleTag(tg.tagNo)"
      >
        {{ tg.name }}
      </text>
      <text
        v-if="pickedTags.length || level"
        class="sh-chip sh-chip--primary"
        @tap="saveAsSegment"
      >
        ＋ {{ $t("members.saveSegment") }}
      </text>
    </view>

    <view class="search">
      <input
        v-model="phone"
        class="field__input"
        type="number"
        maxlength="11"
        :placeholder="$t('members.phonePh')"
        @confirm="load"
      />
    </view>
    <text v-if="phonePartial" class="sh-muted sh-hint">{{ $t("members.phonePartial") }}</text>

    <sh-empty v-if="!list.length && !loading" :text="String($t('members.empty'))"></sh-empty>

    <view v-for="m in list" :key="m.memberNo" class="sh-row sh-card sh-mt-sm" @tap="open(m)">
      <view class="sh-fill">
        <view class="row__head">
          <text class="row__name">···{{ m.phoneTail || "----" }}</text>
          <text v-if="m.level" class="sh-chip" :class="levelClass(m.level)">
            {{ $t(`members.level.${m.level}`) }}
          </text>
          <text v-if="m.status === 'LEAD'" class="sh-chip">{{ $t("members.lead") }}</text>
        </view>
        <text class="sh-muted sh-num">
          {{ $t("members.stat", { n: m.orderCount, m: money(m.totalSpentMinor) }) }}
        </text>
        <text class="sh-muted">
          {{ $t(`members.source.${m.source}`) }}
          <template v-if="m.daysSinceLast != null">
            · {{ m.daysSinceLast === 0 ? $t("members.today")
              : $t("members.daysAgo", { n: m.daysSinceLast }) }}
          </template>
        </text>
      </view>
      <sh-icon name="chevronRight" :size="18" color="var(--sh-sub)"></sh-icon>
    </view>

    <text v-if="list.length" class="sh-hint sh-mt-md">{{ $t("members.privacyHint") }}</text>

    <!-- 口径开关只给店主：它一改，全主体的分层与所有活动受众跟着变 -->
    <text
      v-if="merchant.can('biz:store:admin')"
      class="settings"
      @tap="go('/pages/member-settings/index')"
    >
      {{ $t("memberSettings.entry") }}
    </text>
  </sh-scaffold>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 12rpx;
}
.tagbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 16rpx;
}
.settings {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.sub {
  display: block;
  margin: 16rpx 0 0;
  font-size: 24rpx;
}
/* 未计入提示：主色浅底，不是警示红 —— 这不是故障，是一个需要解释的差额 */
.notice {
  margin-top: 12rpx;
  padding: 16rpx 20rpx;
  border-radius: 24rpx;
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
  font-size: 24rpx;
  line-height: 1.5;
}
.search {
  margin-top: 16rpx;
}

.row__head {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 6rpx;
}
.row__name {
  font-size: 28rpx;
  font-weight: 600;
}
/* `<text>` 默认 inline —— 不给 block，「6 单 · ¥272」与「下过单 · 今天」会挤成一行 */
.row__main .sh-muted {
  display: block;
  line-height: 1.6;
}

</style>
