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
import { confirm, prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const tags = ref<MemberTag[]>([]);
const busy = ref(false);

const sys = computed(() => tags.value.filter((x) => x.tagType === "SYS"));
const mine = computed(() => tags.value.filter((x) => x.tagType === "MCH"));

async function load() {
  tags.value = await api.mMemberTags().catch(() => []);
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

function toggleEnabled(tg: MemberTag) {
  const enable = tg.status !== "ACTIVE";
  run(() => api.mEditMemberTag(tg.tagNo, { enabled: enable }));
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
  const pick = await new Promise<number>((resolve) => {
    uni.showActionSheet({
      itemList: others.map((x) => x.name),
      success: (r) => resolve(r.tapIndex),
      fail: () => resolve(-1),
    });
  });
  if (pick < 0) return;
  const into = others[pick]!;

  const preview = await api.mMergeMemberTag(tg.tagNo, { intoTagNo: into.tagNo });
  const ok = await confirm({ title: String(t("memberTags.mergeTitle", { a: tg.name, b: into.name })), hint: String(t("memberTags.mergeBody", { n: preview.affectedMembers })) });
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
      <view class="tags">
        <text v-for="tg in sys" :key="tg.tagNo" class="sh-chip">
          {{ tg.name }} {{ tg.count }}
        </text>
      </view>
      <text class="sh-muted hint">{{ $t("memberTags.systemHint") }}</text>
    </view>

    <view class="sh-card mt">
      <view class="row">
        <text class="field__label">{{ $t("memberTags.mine") }}</text>
        <text class="sh-chip sh-chip--primary" @tap="create">{{ $t("memberTags.new") }}</text>
      </view>

      <sh-empty v-if="!mine.length" :text="String($t('memberTags.empty'))"></sh-empty>

      <view v-for="tg in mine" :key="tg.tagNo" class="item">
        <view class="item__main">
          <text class="item__name" :class="{ 'is-off': tg.status !== 'ACTIVE' }">{{ tg.name }}</text>
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

    <text class="tip">{{ $t("memberTags.deleteHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.mt {
  margin-top: 16rpx;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}
.hint {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  line-height: 1.6;
}
.item {
  border-top: 2rpx solid var(--sh-faint);
  padding-top: 16rpx;
  margin-top: 16rpx;
}
.item__main {
  display: flex;
  align-items: baseline;
  gap: 12rpx;
}
.item__name {
  font-size: 28rpx;
  font-weight: 600;
}
.item__name.is-off {
  color: var(--sh-sub);
  text-decoration: line-through;
}
.acts {
  display: flex;
  gap: 24rpx;
  margin-top: 12rpx;
}
.tip {
  display: block;
  margin-top: 24rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
