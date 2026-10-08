<script setup lang="ts">
/**
 * 经营范围文字录入（TDD-经营范围文字录入 AC5/AC6）：写一句话 → 识别 → 确认 → 填进清单。
 *
 * <p>识别结果**只是建议**：确认后写进页面清单的未保存态，预览、保存走原来那一条路 ——
 * 这里不落库。认准的默认勾上、可逐条取消；同名多处要店主点一个；认不出的原样列出，不替他猜。
 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { ScopeParseResult, ServiceArea } from "@shared/types";
import { mergeParsed, type ParsedPick } from "@/shared/scope-merge";

const props = defineProps<{
  visible: boolean;
  /** 页面当前的清单（含未保存的改动） */
  areas: ServiceArea[];
  /** 只做自提：此时「不限地区」不生效，要明说 */
  pickupOnly?: boolean;
  /** 门店坐标：同名区划按远近排候选 */
  near?: { latE6: number; lngE6: number } | null;
}>();
const emit = defineEmits<{ close: []; apply: [areas: ServiceArea[]] }>();
const { t } = useI18n();

const text = ref("");
const busy = ref(false);
const result = ref<ScopeParseResult | null>(null);
/** 认准项里取消勾选的（按 level|refCode） */
const off = ref<Set<string>>(new Set());
/** 同名项点中的那个：phrase 下标 → refCode */
const chosen = ref<Record<number, string>>({});
const replace = ref(false);

watch(() => props.visible, (v) => {
  if (v) return;
  // 关掉就清空：下次打开是一次新的录入，不该带着上一次的半截结果
  text.value = "";
  result.value = null;
  off.value = new Set();
  chosen.value = {};
  replace.value = false;
});

const keyOf = (a: { level: string; refCode: string }) => `${a.level}|${a.refCode}`;

async function parse() {
  const s = text.value.trim();
  if (!s || busy.value) return;
  busy.value = true;
  try {
    const r = await api.mRegionParse(s, props.near ?? undefined);
    result.value = r;
    /*
     * 说了「全国」又点名了某处纳入（「全国发货，龙华区自送」）：两句话互相抵消 ——
     * 只要清单里还有一条纳入，就不再是「不限」。纳入项默认不勾，店主真要就自己勾上。
     */
    off.value = new Set(r.unlimited ? r.items.filter((a) => a.mode !== "EXCLUDE").map(keyOf) : []);
    chosen.value = {};
  } finally {
    busy.value = false;
  }
}

function toggle(k: string) {
  const next = new Set(off.value);
  if (next.has(k)) next.delete(k);
  else next.add(k);
  off.value = next;
}

function choose(i: number, refCode: string) {
  chosen.value = { ...chosen.value, [i]: chosen.value[i] === refCode ? "" : refCode };
}

const picks = computed<ParsedPick[]>(() => {
  const r = result.value;
  if (!r) return [];
  const out: ParsedPick[] = r.items.filter((a) => !off.value.has(keyOf(a)));
  r.ambiguous.forEach((g, i) => {
    const c = g.candidates.find((x) => x.refCode === chosen.value[i]);
    if (c) out.push({ mode: g.mode, level: c.level, refCode: c.refCode, name: c.name });
  });
  return out;
});

const merged = computed(() =>
  mergeParsed(props.areas, picks.value, { replace: replace.value, unlimited: !!result.value?.unlimited }));

const canApply = computed(() => picks.value.length > 0 || !!result.value?.unlimited);
const nothing = computed(() => {
  const r = result.value;
  return !!r && !r.unlimited && !r.items.length && !r.ambiguous.length;
});

function apply() {
  if (!canApply.value) return;
  emit("apply", merged.value.areas);
  emit("close");
}

/** 「广东省 / 深圳市 / 龙华区」→ 主标题「龙华区」、副标题「广东省 · 深圳市」（与清单同一种拆法） */
function split(name: string) {
  const parts = name.split(" / ");
  return { main: parts[parts.length - 1] ?? name, path: parts.slice(0, -1).join(" · ") };
}
</script>

<template>
  <sh-sheet :visible="visible" fit :title="String(t('store.text.title'))" @close="emit('close')">
    <textarea v-model="text" class="field__area" :placeholder="String(t('store.text.ph'))" maxlength="300" />
    <text class="sh-hint">{{ t("store.text.example") }}</text>
    <view class="sh-btn sh-btn--soft scope-text__parse" :class="{ 'is-disabled': !text.trim() || busy }" @tap="parse">
      {{ busy ? "…" : t("store.text.parse") }}
    </view>

    <view v-if="result" class="scope-text__result">
      <view v-if="result.unlimited" class="sh-row sh-row--divided">
        <text class="txt-body sh-fill">{{ t("store.text.unlimited") }}</text>
        <text class="sh-chip sh-chip--primary">{{ t("store.text.include") }}</text>
      </view>
      <text v-if="result.unlimited && pickupOnly" class="txt-caption is-warning">{{ t("store.text.pickupOnly") }}</text>

      <view
        v-for="a in result.items"
        :key="keyOf(a)"
        class="sh-row sh-row--divided"
        @tap="toggle(keyOf(a))"
      >
        <sh-check :model-value="!off.has(keyOf(a))"></sh-check>
        <view class="sh-fill">
          <text class="txt-body scope-text__name">{{ split(a.name).main }}</text>
          <text v-if="split(a.name).path" class="txt-caption scope-text__name">{{ split(a.name).path }}</text>
        </view>
        <text class="sh-chip" :class="a.mode === 'EXCLUDE' ? 'sh-chip--danger' : 'sh-chip--primary'">
          {{ a.mode === "EXCLUDE" ? t("store.text.exclude") : t("store.text.include") }}
        </text>
      </view>

      <view v-for="(g, i) in result.ambiguous" :key="`amb-${i}`" class="scope-text__amb">
        <text class="txt-body">{{ t("store.text.pickOne", { p: g.phrase, n: g.candidates.length }) }}</text>
        <view class="sh-wrap scope-text__chips">
          <text
            v-for="c in g.candidates"
            :key="c.refCode"
            class="sh-chip"
            :class="{ 'sh-chip--primary': chosen[i] === c.refCode }"
            @tap="choose(i, c.refCode)"
          >{{ split(c.name).path ? `${split(c.name).path} · ${split(c.name).main}` : split(c.name).main }}</text>
        </view>
      </view>

      <text v-for="u in result.unmatched" :key="`un-${u}`" class="txt-caption txt-quiet scope-text__line">
        {{ t("store.text.unmatched", { p: u }) }}
      </text>
      <text v-if="nothing" class="txt-caption txt-quiet scope-text__line">{{ t("store.text.empty") }}</text>

      <view class="sh-row sh-row--divided" @tap="replace = !replace">
        <text class="txt-body sh-fill">{{ t("store.text.replace") }}</text>
        <sh-switch :model-value="replace"></sh-switch>
      </view>
      <text v-if="merged.droppedIncludes" class="txt-caption is-warning scope-text__line">
        {{ t("store.text.dropped", { n: merged.droppedIncludes }) }}
      </text>
    </view>

    <template #foot>
      <view class="sh-btn" :class="{ 'is-disabled': !canApply }" @tap="apply">
        {{ t("store.text.apply", { n: picks.length }) }}
      </view>
    </template>
  </sh-sheet>
</template>

<style scoped>
.scope-text__parse {
  margin-top: 16rpx;
}
.scope-text__parse.is-disabled,
.sh-btn.is-disabled {
  opacity: 0.5;
}
.scope-text__result {
  margin-top: 24rpx;
}
.scope-text__name {
  display: block;
}
.scope-text__amb {
  padding: 16rpx 0;
}
.scope-text__chips {
  margin-top: 12rpx;
}
.scope-text__line {
  display: block;
  margin-top: 8rpx;
}
</style>
