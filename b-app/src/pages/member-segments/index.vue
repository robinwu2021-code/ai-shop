<script setup lang="ts">
// 人群（P3）：一组筛选条件，可命名保存、反复用。
//
// **存的是条件不是名单**。所以这一页显示的「{n} 人」永远带一句「算于 X」——
// 那是上次算的结果，发券那一刻会重算。把它当成当前人数展示，
// 商家就会照着一份两周前的名单做决定，而没有任何东西会提醒他名单旧了。
//
// 人群从会员列表「另存为人群」建，这一页只负责看、改名、删 ——
// 条件在哪儿筛就在哪儿存，比在这里再做一遍筛选器少一半代码，也少一处口径。
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { MemberSegment, MemberTag } from "@shared/types";
import { confirm, prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const list = ref<MemberSegment[]>([]);
const tags = ref<MemberTag[]>([]);
const busy = ref(false);

async function load() {
  const [sg, tg] = await Promise.all([
    api.mMemberSegments().catch(() => []),
    api.mMemberTags().catch(() => []),
  ]);
  list.value = sg;
  tags.value = tg;
}

async function run(fn: () => Promise<unknown>) {
  if (busy.value) return;
  busy.value = true;
  try {
    await fn();
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/** 条件摘要：**标签显示名字**（存的是号），门店显示店名 —— 号对商家没有意义 */
function summary(sg: MemberSegment) {
  const parts: string[] = [];
  if (sg.scopeStoreNo) parts.push(storeName(sg.scopeStoreNo));
  if (sg.rule.level) parts.push(String(t(`members.level.${sg.rule.level}`)));
  for (const no of sg.rule.tagNos ?? []) {
    parts.push(tags.value.find((x) => x.tagNo === no)?.name ?? no);
  }
  return parts.length ? parts.join(" · ") : String(t("memberSegments.allMembers"));
}

function storeName(no: string) {
  return merchant.stores.find((s) => s.storeNo === no)?.name || no;
}

function countedAt(ts?: number | null) {
  if (!ts) return "";
  const d = new Date(ts);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

async function rename(sg: MemberSegment) {
  const input = await prompt({ title: String(t("memberSegments.rename")), value: sg.name });
  const name = (input ?? "").trim();
  if (!name || name === sg.name) return;
  run(() => api.mSaveMemberSegment({
    segmentNo: sg.segmentNo,
    name,
    scopeStoreNo: sg.scopeStoreNo ?? undefined,
    rule: sg.rule,
  }));
}

async function remove(sg: MemberSegment) {
  if (await confirm({ title: String(t("memberSegments.removeTitle", { name: sg.name })), hint: String(t("memberSegments.removeBody")), danger: true })) {
    run(() => api.mRemoveMemberSegment(sg.segmentNo));
  }
}

/** 重算一次：条件原样存回去，服务端顺手把命中人数刷新 */
function recount(sg: MemberSegment) {
  run(() => api.mSaveMemberSegment({
    segmentNo: sg.segmentNo,
    name: sg.name,
    scopeStoreNo: sg.scopeStoreNo ?? undefined,
    rule: sg.rule,
  }));
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="memberSegments.title" :denied="!merchant.can('biz:customer')">
    <sh-empty v-if="!list.length" :text="String($t('memberSegments.empty'))"></sh-empty>

    <view v-for="sg in list" :key="sg.segmentNo" class="sh-card item">
      <view class="item__head">
        <text class="item__name">{{ sg.name }}</text>
        <text class="sh-num count">{{ $t("memberSegments.count", { n: sg.lastCount }) }}</text>
      </view>
      <text class="sh-muted cond">{{ summary(sg) }}</text>
      <!-- 「算于」不是装饰：它是这份数字唯一的保质期标记 -->
      <text class="sh-muted stamp">
        {{ $t("memberSegments.countedAt", { t: countedAt(sg.countedAt) }) }}
      </text>
      <view class="acts">
        <text class="sh-link" @tap="recount(sg)">{{ $t("memberSegments.recount") }}</text>
        <text class="sh-link" @tap="rename(sg)">{{ $t("memberSegments.rename") }}</text>
        <text class="sh-link" @tap="remove(sg)">{{ $t("memberSegments.remove") }}</text>
      </view>
    </view>

    <text class="tip">{{ $t("memberSegments.ruleHint") }}</text>
    <text class="tip">{{ $t("memberSegments.reachHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.item {
  margin-bottom: 16rpx;
}
.item__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.item__name {
  font-size: 30rpx;
  font-weight: 600;
}
.count {
  font-size: 26rpx;
  color: var(--sh-primary-text);
}
.cond {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
}
.stamp {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
}
.acts {
  display: flex;
  gap: 24rpx;
  margin-top: 16rpx;
}
.tip {
  display: block;
  margin-top: 24rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
