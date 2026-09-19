<script setup lang="ts">
/*
 * 选人面板（原型 m12 / m13）。活动、发券、发消息三处共用这一块 —— 此前三处各有一份「给谁」，
 * 选项还不一样（活动四项、发券四项加人群、发消息只有人群）。
 *
 * 分层、标签、人群、来源四组多选，**组内组间都取或**：「沉睡的 + 爱囤货的都给」是一次发给两拨人。
 * 这与筛选页「同时含以下标签」相反，所以组标题上写「满足任一即可」。
 * 活动多两行：「所有人」（= 不选）与「非本店会员」（与其余互斥）。
 *
 * 底部两个数当场算：命中是「符合条件的人」，收得到是「消息发得到的人」。只给一个，
 * 商家发完会以为少发了。活动不推送，只给「覆盖」。
 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { audienceLabel, itemKey } from "@/shared/audience";
import type { AudienceItem, AudiencePreview, MemberSegment, MemberStats, MemberTag } from "@shared/types";

const props = defineProps<{
  visible: boolean;
  modelValue: AudienceItem[];
  /** 活动场景：多「所有人 / 非本店会员」两行，只算覆盖不算收得到 */
  forActivity?: boolean;
  /** 发消息的场景（NOTICE / WAKEUP / COUPON），频次闸按它判 */
  scene?: string;
}>();
const emit = defineEmits<{ close: []; confirm: [items: AudienceItem[], label: string] }>();

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));

const LEVELS = ["NEW", "REGULAR", "LOYAL", "SLEEPING"] as const;
const SOURCES = ["ORDER", "SHARE", "SCAN", "MANUAL", "FAVORITE", "SEARCH"] as const;

const stats = ref<MemberStats | null>(null);
const tags = ref<MemberTag[]>([]);
const segments = ref<MemberSegment[]>([]);
const picked = ref<AudienceItem[]>([]);
const preview = ref<AudiencePreview | null>(null);
const loaded = ref(false);

async function loadNames() {
  if (loaded.value) return;
  const [st, tg, sg] = await Promise.all([api.mMemberStats(), api.mMemberTags(), api.mMemberSegments()]);
  stats.value = st;
  tags.value = tg.filter((x) => x.tagType === "MCH" && x.status === "ACTIVE");
  segments.value = sg;
  loaded.value = true;
}

watch(() => props.visible, (v) => {
  if (!v) return;
  picked.value = props.modelValue.map((x) => ({ ...x }));
  void loadNames();
}, { immediate: true });

const nonMember = computed(() => picked.value.some((x) => x.type === "NON_MEMBER"));
const isOn = (it: AudienceItem) => picked.value.some((x) => itemKey(x) === itemKey(it));

function toggle(it: AudienceItem) {
  if (nonMember.value && it.type !== "NON_MEMBER") return;
  picked.value = isOn(it)
    ? picked.value.filter((x) => itemKey(x) !== itemKey(it))
    : [...picked.value, it];
}

function pickEveryone() {
  picked.value = [];
}

/** 「非本店会员」与其余互斥：沉睡的一定是会员，两个一起选自相矛盾 */
function pickNonMember() {
  picked.value = nonMember.value ? [] : [{ type: "NON_MEMBER", value: "*" }];
}

const levelCount = (lv: string) => {
  const s = stats.value;
  if (!s) return null;
  return { NEW: s.newCount, REGULAR: s.regularCount, LOYAL: s.loyalCount, SLEEPING: s.sleepingCount }[lv] ?? null;
};

/*
 * 当场试算。连点几下只发最后一次 —— 每点一格就打一个请求，慢的那个后回来会把数字刷回旧的。
 */
let timer: ReturnType<typeof setTimeout> | null = null;
let seq = 0;
watch(picked, (items) => {
  if (timer) clearTimeout(timer);
  if (!items.length) {
    preview.value = null;
    return;
  }
  timer = setTimeout(async () => {
    const mine = ++seq;
    try {
      const r = await api.mAudiencePreview({ audiences: items, scene: props.scene, forActivity: props.forActivity });
      if (mine === seq) preview.value = r;
    } catch {
      if (mine === seq) preview.value = null;
    }
  }, 300);
}, { deep: true });

const summary = computed(() => {
  if (!picked.value.length) return props.forActivity ? tt("audience.coverAll") : tt("audience.none");
  if (nonMember.value) return tt("audience.coverNonMember");
  const p = preview.value;
  if (!p || p.matched == null) return "…";
  if (props.forActivity) return tt("audience.cover", { n: p.matched });
  return tt("audience.matched", { n: p.matched, m: p.reachable ?? 0 });
});

const skipLine = computed(() => {
  const p = preview.value;
  if (props.forActivity || !p?.skips.length) return "";
  return p.skips.map((s) => tt(`reach.reason.${s.reason}`, { n: s.count })).join(" · ");
});

/** 发券 / 发消息必须说清发给谁；活动不选 = 所有人 */
const canConfirm = computed(() => props.forActivity || picked.value.length > 0);

function confirmPick() {
  if (!canConfirm.value) return;
  emit("confirm", picked.value, audienceLabel(picked.value,
    { tags: tags.value, segments: segments.value }, tt));
}
</script>

<template>
  <sh-sheet :visible="visible" :title="tt(forActivity ? 'audience.activityTitle' : 'audience.title')" @close="emit('close')">
    <template v-if="forActivity">
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between" @tap="pickEveryone">
          <text class="txt-body" :class="{ 'txt-primary': !picked.length }">{{ $t("audience.everyone") }}</text>
          <sh-icon v-if="!picked.length" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
        </view>
        <view class="sh-cell sh-row sh-row--between" @tap="pickNonMember">
          <text class="txt-body" :class="{ 'txt-primary': nonMember }">{{ $t("audience.nonMember") }}</text>
          <view class="sh-row">
            <text class="txt-caption sh-muted">{{ $t("audience.nonMemberHint") }}</text>
            <sh-icon v-if="nonMember" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
          </view>
        </view>
      </view>
    </template>

    <view class="sh-row sh-row--between grp">
      <text class="txt-caption sh-muted">{{ $t("audience.level") }}</text>
      <text class="txt-caption sh-muted">{{ nonMember ? $t("audience.exclusive") : $t("audience.anyOf") }}</text>
    </view>
    <view class="sh-cells">
      <view v-for="lv in LEVELS" :key="lv" class="sh-cell sh-row sh-row--between"
            :class="{ 'is-off': nonMember }" @tap="toggle({ type: 'LEVEL', value: lv })">
        <text class="txt-body" :class="{ 'txt-primary': isOn({ type: 'LEVEL', value: lv }) }">{{ $t(`members.level.${lv}`) }}</text>
        <view class="sh-row">
          <text class="txt-body sh-muted sh-num">{{ levelCount(lv) ?? "" }}</text>
          <sh-icon v-if="isOn({ type: 'LEVEL', value: lv })" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
        </view>
      </view>
    </view>

    <template v-if="tags.length">
      <text class="txt-caption sh-muted grp">{{ $t("audience.tags") }}</text>
      <view class="sh-cells">
        <view v-for="tg in tags" :key="tg.tagNo" class="sh-cell sh-row sh-row--between"
              :class="{ 'is-off': nonMember }" @tap="toggle({ type: 'TAG', value: tg.tagNo })">
          <text class="txt-body" :class="{ 'txt-primary': isOn({ type: 'TAG', value: tg.tagNo }) }">{{ tg.name }}</text>
          <view class="sh-row">
            <text class="txt-body sh-muted sh-num">{{ tg.count }}</text>
            <sh-icon v-if="isOn({ type: 'TAG', value: tg.tagNo })" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
          </view>
        </view>
      </view>
    </template>

    <template v-if="segments.length">
      <text class="txt-caption sh-muted grp">{{ $t("audience.segments") }}</text>
      <view class="sh-cells">
        <view v-for="sg in segments" :key="sg.segmentNo" class="sh-cell sh-row sh-row--between"
              :class="{ 'is-off': nonMember }" @tap="toggle({ type: 'SEGMENT', value: sg.segmentNo })">
          <text class="txt-body" :class="{ 'txt-primary': isOn({ type: 'SEGMENT', value: sg.segmentNo }) }">{{ sg.name }}</text>
          <view class="sh-row">
            <text class="txt-body sh-muted sh-num">{{ sg.lastCount }}</text>
            <sh-icon v-if="isOn({ type: 'SEGMENT', value: sg.segmentNo })" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
          </view>
        </view>
      </view>
    </template>

    <text class="txt-caption sh-muted grp">{{ $t("audience.sources") }}</text>
    <view class="sh-cells">
      <view v-for="src in SOURCES" :key="src" class="sh-cell sh-row sh-row--between"
            :class="{ 'is-off': nonMember }" @tap="toggle({ type: 'SOURCE', value: src })">
        <text class="txt-body" :class="{ 'txt-primary': isOn({ type: 'SOURCE', value: src }) }">{{ $t(`members.source.${src}`) }}</text>
        <sh-icon v-if="isOn({ type: 'SOURCE', value: src })" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
      </view>
    </view>

    <template #foot>
      <view class="sum">
        <text class="txt-strong txt-primary sh-num">{{ summary }}</text>
        <text v-if="skipLine" class="txt-caption sh-muted sum__skip">{{ skipLine }}</text>
      </view>
      <view class="sh-row bar">
        <view class="sh-btn sh-btn--muted sh-fill" @tap="emit('close')">{{ $t("audience.cancel") }}</view>
        <view class="sh-btn bar__main" :class="{ 'is-disabled': !canConfirm }" @tap="confirmPick">{{ $t("audience.ok") }}</view>
      </view>
    </template>
  </sh-sheet>
</template>

<style scoped>
.grp {
  display: flex;
  padding: 16rpx 8rpx 0;
}
.is-off {
  opacity: 0.4;
}
.sum {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  padding-bottom: 16rpx;
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
</style>
