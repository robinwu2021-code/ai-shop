<script setup lang="ts">
// 标签管理（P2）：改名 / 停用 / 合并。
//
// **系统标签只读**：它的名字就是口径（「沉睡」= 60 天没来）。允许改名之后，
// 两个商家对同一个词会有两种理解，而按它筛出来的人群从此不可比。
//
// **合并前先算影响面**：合并不可逆，所以界面必须先把「多少人会改、其中多少人
// 两个标签都有」摆出来，再让他按。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { MemberTag } from "@shared/types";
import { confirm, pick, prompt } from "@ai-shop/ui/prompt";
import { ROUTES } from "@/shared/nav";

const { t } = useI18n();
const merchant = useMerchantStore();

const tags = ref<MemberTag[]>([]);
const busy = ref(false);

const sys = computed(() => tags.value.filter((x) => x.tagType === "SYS"));
const mine = computed(() => tags.value.filter((x) => x.tagType === "MCH"));

/** 首屏到过没有。**不是 `loading`** —— 那个含下拉刷新，刷新时把列表换成空态是另一个 bug */
const loaded = ref(false);
/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「还没有…」 */
const failed = ref(false);

async function load() {
  try {
    tags.value = await api.mMemberTags();
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
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

async function create() {
  const name = ((await prompt({
    title: String(t("memberTags.newTitle")),
    placeholder: String(t("memberTags.newPh")),
  })) ?? "").trim();
  if (!name) return;
  run(() => api.mCreateMemberTag(name));
}

async function rename(tg: MemberTag) {
  const name = ((await prompt({ title: String(t("memberTags.rename")), value: tg.name })) ?? "").trim();
  if (!name || name === tg.name) return;
  run(() => api.mEditMemberTag(tg.tagNo, { name }));
}

/**
 * 停用前先看引用（AC-16）：引用它的活动从停用那一刻起一个人都命中不了，
 * 而活动照样显示「进行中」。0 处在用时直接停，不打扰。
 */
async function toggleEnabled(tg: MemberTag) {
  const enable = tg.status !== "ACTIVE";
  if (!enable) {
    const u = await api.mMemberTagUsage(tg.tagNo).catch(() => null);
    const n = (u?.activities.length ?? 0) + (u?.segments.length ?? 0);
    if (n > 0) {
      const ok = await confirm({
        title: String(t("memberTags.disableTitle", { a: tg.name })),
        hint: String(t("memberTags.disableUsed", { a: u!.activities.length, s: u!.segments.length })),
      });
      if (!ok) return;
    }
  }
  run(() => api.mEditMemberTag(tg.tagNo, { enabled: enable }));
}

function openTag(tg: MemberTag) {
  uni.navigateTo({ url: `${ROUTES.memberTag}?tagNo=${tg.tagNo}` });
}

/**
 * 合并。**两步**：先试算拿到影响面，摆给他看，确认之后才落库。
 * 一步到位的话，他按下去之前不知道会改多少人 —— 而这是不可逆的。
 */
async function merge(tg: MemberTag) {
  const others = mine.value.filter((x) => x.tagNo !== tg.tagNo && x.status === "ACTIVE");
  if (!others.length) {
    uni.showToast({ title: t("memberTags.mergeNoTarget"), icon: "none" });
    return;
  }
  const idx = await pick({
    title: String(t("memberTags.mergeInto")),
    items: others.map((x) => x.name),
  });
  if (idx === null) return;
  const into = others[idx]!;

  const preview = await api.mMergeMemberTag(tg.tagNo, { intoTagNo: into.tagNo });
  const body = [String(t("memberTags.mergeBody", { n: preview.affectedMembers }))];
  // 引用源标签的活动与人群会一起改指到目标标签 —— 不写出来，他会以为活动的受众被悄悄换了
  if (preview.referencedActivities > 0) {
    body.push(String(t("memberTags.mergeActivities", { n: preview.referencedActivities })));
  }
  const ok = await confirm({ title: String(t("memberTags.mergeTitle", { a: tg.name, b: into.name })), hint: body.join("\n") });
  if (!ok) return;
  await run(() => api.mMergeMemberTag(tg.tagNo, { intoTagNo: into.tagNo, confirm: true }));
  uni.showToast({ title: t("memberTags.merged"), icon: "none" });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="memberTags.title" :denied="!merchant.can('biz:customer')">
    <!-- 系统标签：只读。口径公开可查，但不给任何编辑入口 -->
    <view v-if="sys.length" class="sh-card">
      <text class="field__label">{{ $t("memberTags.system") }}</text>
      <view class="tags sh-wrap">
        <text v-for="tg in sys" :key="tg.tagNo" class="sh-chip">
          {{ tg.name }} {{ tg.count }}
        </text>
      </view>
      <text class="sh-muted sh-hint">{{ $t("memberTags.systemHint") }}</text>
    </view>

    <view class="sh-card sh-mt-sm">
      <view class="sh-row sh-row--between">
        <text class="field__label">{{ $t("memberTags.mine") }}</text>
        <text class="sh-chip sh-chip--primary" @tap="create">{{ $t("memberTags.new") }}</text>
      </view>

      <sh-empty v-if="!mine.length" :pending="!loaded" :failed="failed" @retry="load" :text="String($t('memberTags.empty'))"></sh-empty>

      <view v-for="tg in mine" :key="tg.tagNo" class="item sh-mt-sm">
        <view class="sh-row sh-row--baseline">
          <text class="txt-strong" :class="{ 'sh-void': tg.status !== 'ACTIVE' }" @tap="openTag(tg)">{{ tg.name }} ›</text>
          <text class="sh-muted">
            {{ $t("memberTags.count", { n: tg.count }) }}
            <template v-if="tg.status !== 'ACTIVE'"> · {{ $t("memberTags.disabled") }}</template>
          </text>
        </view>
        <view class="acts">
          <text class="sh-link" @tap="rename(tg)">{{ $t("memberTags.rename") }}</text>
          <text class="sh-link" @tap="merge(tg)">{{ $t("memberTags.merge") }}</text>
          <text class="sh-link" @tap="toggleEnabled(tg)">
            {{ tg.status === "ACTIVE" ? $t("memberTags.disable") : $t("memberTags.enable") }}
          </text>
        </view>
      </view>
    </view>

    <text class="sh-hint sh-mt-md">{{ $t("memberTags.deleteHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.tags {
  margin-top: 12rpx;
}

.item {
  border-top: var(--sh-hairline-soft);
  padding-top: 16rpx;
}

.acts {
  display: flex;
  gap: 24rpx;
  margin-top: 12rpx;
}
</style>
