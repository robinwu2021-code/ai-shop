<script setup lang="ts">
// 会员详情（P1）。三块：他是谁、各店往来、他是怎么来的。
//
// **「谁发的链接」必须写出来**：只记「来自分享」的话，分享激励没法结算，
// 商家也不知道该谢谁 —— 而那句「李姐帮我拉来的」正是他会记住的东西。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import { monthDay } from "@shared/utils/datetime";
import type { MemberDetail, MemberTag } from "@shared/types";
import { useI18n } from "vue-i18n";

const merchant = useMerchantStore();
const data = ref<MemberDetail | null>(null);
const memberNo = ref("");

/** 多店主体才显示「各店往来」—— 单店时它与上面的总数一模一样 */
/** 最近触达那一行（m04）：「09-12 唤回 · 已下单」—— 商家打电话前先看到上周发过、他来没来 */
const lastReachText = computed(() => {
  const r = data.value?.lastReach;
  if (!r) return "";
  const outcome = r.orderedAt ? "ORDERED" : r.openedAt ? "OPENED" : "NONE";
  return tt("memberDetail.lastReachLine", {
    d: monthDay(r.sentAt), s: tt(`reach.scene.${r.scene}`), o: tt(`memberDetail.reachOutcome.${outcome}`),
  });
});

function openLastReach() {
  const r = data.value?.lastReach;
  if (r) uni.navigateTo({ url: `/pages/reach-task/index?taskNo=${r.taskNo}` });
}

const showStores = computed(() => merchant.multiStore && (data.value?.stores.length ?? 0) > 0);

function storeName(no?: string | null) {
  if (!no) return "—";
  return merchant.stores.find((s) => s.storeNo === no)?.name || no;
}

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));

/** 每人最多几个商家标签。与后端 member.tag.max-per-member 的默认值一致；超了后端会拒并写明上限 */
const MAX_TAGS = 10;

/** 他身上的商家标签。系统标签（分层）不在这里 —— 那是按口径算的，不能手改 */
const mine = computed(() => (data.value?.tags ?? []).filter((x) => x.tagType === "MCH"));

// 选标签弹层（原型 m05）：多选，勾 / 去勾，按「保存」一次提交差集
const showTags = ref(false);
const allTags = ref<MemberTag[]>([]);
/** 标签字典拉过没有 / 拉失败没有 —— 没拉到之前不该显示「还没有标签」 */
const tagsLoaded = ref(false);
const tagsFailed = ref(false);
const picked = ref<string[]>([]);
const saving = ref(false);

async function openTags() {
  picked.value = mine.value.map((x) => x.tagNo);
  showTags.value = true;
  await loadTags();
}

async function loadTags() {
  try {
    allTags.value = (await api.mMemberTags()).filter((x) => x.tagType === "MCH" && x.status === "ACTIVE");
    tagsFailed.value = false;
  } catch {
    tagsFailed.value = true;
  }
  tagsLoaded.value = true;
}

function toggleTag(no: string) {
  if (picked.value.includes(no)) {
    picked.value = picked.value.filter((x) => x !== no);
  } else if (picked.value.length < MAX_TAGS) {
    picked.value = [...picked.value, no];
  } else {
    uni.showToast({ title: tt("memberDetail.tagFull", { n: MAX_TAGS }), icon: "none" });
  }
}

async function saveTags() {
  if (saving.value) return;
  const before = mine.value.map((x) => x.tagNo);
  const add = picked.value.filter((x) => !before.includes(x));
  const remove = before.filter((x) => !picked.value.includes(x));
  if (!add.length && !remove.length) {
    showTags.value = false;
    return;
  }
  saving.value = true;
  try {
    await api.mTagMembers({ memberNos: [memberNo.value], add, remove });
    showTags.value = false;
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

/** 这次没取到。**与「这儿本来就没有」是两件事** —— 整页内容都挂在拉来的数据后面，
 *  拉不到就是一个只有标题栏的空白页。交给 `sh-scaffold` 的 `failed` 说出来 */
const failed = ref(false);

async function load() {
  try {
    data.value = await api.mMemberDetail(memberNo.value);
    failed.value = false;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
    failed.value = true;
  }
}

onLoad(async (q) => {
  memberNo.value = q?.memberNo ?? "";
  await merchant.ensureStores().catch(() => null);
  await load();
});
</script>

<template>
  <sh-scaffold title-key="memberDetail.title" :denied="!merchant.can('biz:customer')"
    :failed="failed"
    @retry="load"
  >
    <template v-if="data">
      <view class="sh-card">
        <view class="sh-row row">
          <text class="txt-title sh-num">{{ $t("members.phoneTail", { n: data.member.phoneTail || "----" }) }}</text>
          <text v-if="data.member.level" class="sh-chip"
            :class="data.member.level === 'SLEEPING' ? 'sh-chip--warning' : 'sh-chip--primary'">
            {{ $t(`members.level.${data.member.level}`) }}
          </text>
        </view>
        <text class="sh-muted sh-mt-xs blk">
          {{ $t("memberDetail.joined", { s: monthDay(data.member.joinedAt) }) }}
          · {{ $t(`members.source.${data.member.source}`) }}
        </text>
        <text v-if="data.member.firstStoreNo" class="sh-muted">
          {{ $t("memberDetail.firstStore", { s: storeName(data.member.firstStoreNo) }) }}
        </text>
        <sh-kv between :label="String($t('memberDetail.lifetime'))" class="txt-sub sh-mt-xs blk">
          <text class="txt-bold sh-num">
            {{ $t("members.stat", {
              n: data.member.orderCount, m: money(data.member.totalSpentMinor) }) }}
          </text>
        </sh-kv>
        <sh-kv between :label="String($t('memberDetail.d90'))" class="txt-sub">
          <text class="txt-bold sh-num">{{ data.member.d90OrderCount }}</text>
        </sh-kv>
      </view>

      <!--
        标签（原型 m04）。整行可点，打开选标签弹层 —— 此前后端能打标签、页面上一个入口都没有。
        线索会员也能打：打了不等于能给他发消息，那一条在选人面板里会写成「手录未同意」。
      -->
      <view class="sh-card sh-mt-sm sh-row sh-row--between" @tap="openTags">
        <view class="sh-fill">
          <text class="txt-title">{{ $t("memberDetail.tags") }}</text>
          <view v-if="mine.length" class="sh-wrap tags">
            <text v-for="tg in mine" :key="tg.tagNo" class="sh-chip">{{ tg.name }}</text>
          </view>
          <text v-else class="sh-muted blk">{{ $t("memberDetail.noTags") }}</text>
        </view>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>

      <!-- 最近触达（原型 m04）：没发过就不占这一行 -->
      <view v-if="data.lastReach" class="sh-card sh-mt-sm sh-row sh-row--between" @tap="openLastReach">
        <view class="sh-fill">
          <text class="txt-title">{{ $t("memberDetail.lastReach") }}</text>
          <text class="sh-muted blk">{{ lastReachText }}</text>
        </view>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>

      <!-- 各店往来：多店商家问的是「南门店有多少熟客」，单店没有这个问题 -->
      <view v-if="showStores" class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t("memberDetail.stores") }}</text>
        <view v-for="s in data.stores" :key="s.storeNo" class="txt-sub kv line">
          <text>
            {{ storeName(s.storeNo) }}
            <text v-if="s.isFirstStore" class="sh-chip">{{ $t("memberDetail.firstTag") }}</text>
          </text>
          <text class="txt-bold sh-num">
            {{ $t("members.stat", { n: s.orderCount, m: money(s.totalSpentMinor) }) }}
          </text>
        </view>
      </view>

      <!-- 来源轨迹：谁发的链接、哪个员工录的，都要写出来 -->
      <view class="sh-card sh-mt-sm">
        <text class="txt-title">{{ $t("memberDetail.sources") }}</text>
        <view v-for="(s, i) in data.sources" :key="i" class="txt-sub kv line">
          <text>
            {{ monthDay(s.occurredAt) }} · {{ $t(`members.source.${s.sourceType}`) }}
          </text>
          <text class="sh-muted">
            <!-- 不显示内部账号号（U2026…）：商家认不出是谁，而且那是内部标识 -->
            <template v-if="s.inviterUserNo">
              {{ $t(s.inviterRole === "STAFF" ? "memberDetail.byInviterStaff" : "memberDetail.byInviterCustomer") }}
            </template>
            <template v-else-if="s.operatorNo">
              {{ $t("memberDetail.byStaff") }}
            </template>
            <template v-else>{{ storeName(s.storeNo) }}</template>
          </text>
        </view>
      </view>

      <text class="sh-hint sh-mt-md">{{ $t("members.privacyHint") }}</text>

      <sh-sheet :visible="showTags" :title="tt('memberDetail.tagsOf')" @close="showTags = false">
        <view class="sh-cells">
          <view v-for="tg in allTags" :key="tg.tagNo" class="sh-cell sh-row sh-row--between" @tap="toggleTag(tg.tagNo)">
            <text class="txt-body" :class="{ 'txt-primary': picked.includes(tg.tagNo) }">{{ tg.name }}</text>
            <view class="sh-row">
              <text class="txt-body sh-muted sh-num">{{ tg.count }}</text>
              <sh-icon v-if="picked.includes(tg.tagNo)" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
            </view>
          </view>
        </view>
        <sh-empty v-if="!allTags.length" :pending="!tagsLoaded" :failed="tagsFailed" compact bare
                  :text="tt('batchTag.noTags')" @retry="loadTags"></sh-empty>
        <template #foot>
          <text class="txt-caption sh-muted blk foot__hint">{{ $t("memberDetail.tagCount", { n: picked.length, m: MAX_TAGS }) }}</text>
          <view class="sh-row bar">
            <view class="sh-btn sh-btn--muted sh-fill" @tap="showTags = false">{{ $t("batchTag.cancel") }}</view>
            <view class="sh-btn bar__main" :class="{ 'is-disabled': saving }" @tap="saveTags">{{ $t("memberDetail.saveTags") }}</view>
          </view>
        </template>
      </sh-sheet>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.row {
  gap: 12rpx;
}

.blk {
  display: block;
}
.tags {
  gap: 12rpx;
  margin-top: 12rpx;
}
.foot__hint {
  padding-bottom: 16rpx;
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
/* 只留本页版面：排法（两端对齐）归 sh-kv。
   ⚠️ 这个类名与 sh-kv 的根同名，**不要挂到 <sh-kv> 上** ——
   小程序上调用点的 class 会同时落在宿主与组件根，内边距吃两遍。下面两处是普通行 */
.kv {
  padding: 8rpx 0;
}
.kv.line {
  border-top: var(--sh-hairline-soft);
  padding-top: 12rpx;
  margin-top: 12rpx;
}
</style>
