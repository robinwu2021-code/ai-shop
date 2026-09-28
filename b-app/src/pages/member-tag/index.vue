<script setup lang="ts">
/*
 * 标签详情（原型 m08 / m09）。
 *
 * 此前标签页只有「字典」：改名、停用、合并都在列表上点，**看不到它被谁在用**。
 * 而停用或合并的后果全落在引用方身上 —— 引用它的活动从那一刻起一个人都命中不了，
 * 活动照样显示「进行中」。所以这一页先把「用在哪」摆出来，操作放在底部。
 *
 * 合并会把引用它的活动受众与人群条件一起改指到目标标签（后端 mergeTag），
 * 确认框里写出会改几个活动 —— 不写的话，他会以为活动的受众被悄悄换了。
 */
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { confirm, pick, prompt } from "@ai-shop/ui/prompt";
import type { AudienceRef, MemberTag, MemberTagUsage } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

const tagNo = ref("");
const data = ref<MemberTagUsage | null>(null);
const failed = ref(false);
const busy = ref(false);

const tag = computed(() => data.value?.tag ?? null);
const usedCount = computed(() => (data.value?.activities.length ?? 0) + (data.value?.segments.length ?? 0));

async function load() {
  if (!tagNo.value) return;
  try {
    data.value = await api.mMemberTagUsage(tagNo.value);
    failed.value = false;
  } catch {
    failed.value = true;
  }
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

async function rename() {
  const tg = tag.value;
  if (!tg) return;
  const name = ((await prompt({ title: tt("memberTags.rename"), value: tg.name })) ?? "").trim();
  if (!name || name === tg.name) return;
  run(() => api.mEditMemberTag(tg.tagNo, { name }));
}

/** 停用：有引用时先确认并写明数量（AC-16）；0 处在用直接停 */
async function toggleEnabled() {
  const tg = tag.value;
  if (!tg) return;
  const enable = tg.status !== "ACTIVE";
  if (!enable && usedCount.value > 0) {
    const ok = await confirm({
      title: tt("memberTags.disableTitle", { a: tg.name }),
      hint: tt("memberTags.disableUsed", { a: data.value!.activities.length, s: data.value!.segments.length }),
      danger: true,
    });
    if (!ok) return;
  }
  run(() => api.mEditMemberTag(tg.tagNo, { enabled: enable }));
}

/** 合并（原型 m09）：先试算把影响面摆出来，再让他按 —— 合并不可逆 */
async function merge() {
  const tg = tag.value;
  if (!tg) return;
  const others: MemberTag[] = (await api.mMemberTags())
    .filter((x) => x.tagType === "MCH" && x.status === "ACTIVE" && x.tagNo !== tg.tagNo);
  if (!others.length) {
    uni.showToast({ title: tt("memberTags.mergeNoTarget"), icon: "none" });
    return;
  }
  const idx = await pick({ title: tt("memberTags.mergeInto"), items: others.map((x) => x.name) });
  if (idx === null) return;
  const into = others[idx]!;
  const pv = await api.mMergeMemberTag(tg.tagNo, { intoTagNo: into.tagNo });
  const hint = [tt("memberTags.mergeBody", { n: pv.affectedMembers })];
  if (pv.referencedActivities > 0) hint.push(tt("memberTags.mergeActivities", { n: pv.referencedActivities }));
  const ok = await confirm({ title: tt("memberTags.mergeTitle", { a: tg.name, b: into.name }), hint: hint.join("\n"), danger: true });
  if (!ok) return;
  try {
    await api.mMergeMemberTag(tg.tagNo, { intoTagNo: into.tagNo, confirm: true });
    uni.showToast({ title: tt("memberTags.merged"), icon: "none" });
    // 源标签已并入目标：留在这一页只会看到一个空壳，回到目标标签
    uni.redirectTo({ url: `${ROUTES.memberTag}?tagNo=${into.tagNo}` });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function openRef(r: AudienceRef) {
  uni.navigateTo({ url: `${ROUTES.activityEdit}?activityNo=${r.refNo}` });
}

function openSegment(segmentNo: string) {
  uni.navigateTo({ url: `${ROUTES.memberSegment}?segmentNo=${segmentNo}` });
}

/** 看这些人：回会员名单，按这个标签筛好 */
function viewMembers() {
  uni.navigateTo({ url: `${ROUTES.customers}?tagNo=${tagNo.value}` });
}

onLoad((q) => {
  tagNo.value = String(q?.tagNo ?? "");
});
onShow(load);
</script>

<template>
  <sh-scaffold title-key="memberTag.title" :denied="!merchant.can('biz:customer')" :failed="failed" @retry="load">
    <template v-if="tag && data">
      <sh-stat
        boxed
        :items="[
          { key: 'n', value: tag.count, label: tt('memberTag.members') },
          { key: 'm', value: data.newThisMonth, label: tt('memberTag.thisMonth') },
          { key: 'u', value: usedCount, label: tt('memberTag.used') },
        ]"
      ></sh-stat>

      <view class="sh-cells sh-mt-sm">
        <view class="sh-cell sh-row sh-row--between" @tap="rename">
          <text class="txt-body sh-muted">{{ $t("memberTag.name") }}</text>
          <view class="sh-row">
            <text class="txt-body">{{ tag.name }}</text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("memberTag.status") }}</text>
          <text class="sh-chip" :class="{ 'sh-chip--success': tag.status === 'ACTIVE' }">
            {{ tag.status === "ACTIVE" ? $t("memberTag.active") : $t("memberTags.disabled") }}
          </text>
        </view>
      </view>

      <text class="txt-caption sh-muted grp">{{ $t("memberTag.usedIn") }}</text>
      <view v-if="usedCount" class="sh-cells">
        <view v-for="a in data.activities" :key="a.refNo" class="sh-cell sh-row sh-row--between" @tap="openRef(a)">
          <text class="txt-body">{{ $t("memberTag.activity", { name: a.name || a.refNo }) }}</text>
          <view class="sh-row">
            <text class="sh-chip" :class="{ 'sh-chip--success': a.status === 'RUNNING' }">
              {{ a.status ? $t(`activityEdit.status.${a.status}`) : "" }}
            </text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
        <view v-for="sg in data.segments" :key="sg.segmentNo" class="sh-cell sh-row sh-row--between" @tap="openSegment(sg.segmentNo)">
          <text class="txt-body">{{ $t("memberTag.segment", { name: sg.name }) }}</text>
          <view class="sh-row">
            <text class="txt-body sh-muted sh-num">{{ $t("memberSegments.count", { n: sg.lastCount }) }}</text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
      </view>
      <sh-empty v-else compact bare :text="tt('memberTag.none')"></sh-empty>

      <view class="sh-cells sh-mt-sm">
        <view class="sh-cell sh-row sh-row--between" @tap="viewMembers">
          <text class="txt-body">{{ $t("memberTag.viewMembers", { n: tag.count }) }}</text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>

      <sh-actionbar>
        <view class="sh-row bar">
          <view class="sh-btn sh-btn--muted sh-fill" :class="{ 'is-disabled': busy }" @tap="merge">{{ $t("memberTag.merge") }}</view>
          <view class="sh-btn sh-btn--danger sh-fill" :class="{ 'is-disabled': busy }" @tap="toggleEnabled">
            {{ tag.status === "ACTIVE" ? $t("memberTags.disable") : $t("memberTags.enable") }}
          </view>
        </view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.grp {
  display: block;
  padding: 16rpx 8rpx 0;
}
.bar {
  gap: 16rpx;
  width: 100%;
}
</style>
