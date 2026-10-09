<script setup lang="ts">
/**
 * 物流轨迹（TDD-物流轨迹多渠道；原型 prototypes/logistics-trace.html s01–s03、s06）。
 *
 * <p><b>C 端与 B 端共用这一个件</b>：两端各写一份的下场是改一次文案要改两处，
 * 而漏掉的那一处没人会发现（三端时间线此前就是各写各的）。
 * uni 的 {@code <map>} 在小程序走腾讯底图、在 App 走高德 SDK，同一段模板两端都能跑。
 *
 * <p><b>地图只到城市级</b>：坐标是聚合器给的行政区中心点，不是快件 GPS。
 * 所以右下角那行小字必须留着 —— 不写的话第一个问题一定是「为什么不显示快递车在哪」。
 */
import { computed, ref } from "vue";
import type { ShipmentTrace } from "@shared/types";

import { TRACE_STEPS, traceHasSteps, traceStepIndex } from "@shared/strategies/trace-step";

const props = withDefaults(defineProps<{
  trace: ShipmentTrace;
  /** 折叠阈值：超过这么多条就只显示前几条 + 「展开全部」。实测一单 12 条，全铺开要滑两屏 */
  foldAt?: number;
}>(), { foldAt: 3 });

const expanded = ref(false);

/** 四档步骤条与「走到第几步」都来自 shared —— 摘要行也读同一份，别在两处各写一个三分支 */
const stepKeys = TRACE_STEPS;
const stepIndex = computed(() => traceStepIndex(props.trace));
const showSteps = computed(() => traceHasSteps(props.trace));

/** 微信渠道那一屏只给最新一条，不给整条时间线——两边数据源不同，两份时间线会对不上 */
const latestText = computed(() => props.trace.nodes.at(0)?.text || "");

const shown = computed(() =>
  expanded.value ? props.trace.nodes : props.trace.nodes.slice(0, props.foldAt));
const foldable = computed(() => props.trace.nodes.length > props.foldAt);

/**
 * 地图上的点。**至少两个才画** —— 一个点连不成线，画出来是一张几乎空白的底图，
 * 看着像加载失败（原型 s03 就是这一条）。
 */
const points = computed(() =>
  props.trace.nodes
    .filter((n) => n.latE6 != null && n.lngE6 != null)
    .map((n) => ({
      lat: Number(n.latE6) / 1e6,
      lng: Number(n.lngE6) / 1e6,
      name: n.location || "",
    })));
const hasMap = computed(() => points.value.length >= 2);

/** 折线按时间正序画（节点是倒序的），这样「从起点走到当前」的方向与阅读方向一致 */
const polyline = computed(() => [{
  points: [...points.value].reverse().map((p) => ({ latitude: p.lat, longitude: p.lng })),
  color: "#C8302BDD",
  width: 3,
  dottedLine: true,
}]);

const markers = computed(() => {
  const ps = [...points.value].reverse();
  const out: Record<string, unknown>[] = [];
  const label = (t: string, bg: string) => ({
    content: t, color: "#FFFFFF", fontSize: 10, bgColor: bg,
    padding: 3, borderRadius: 8, anchorX: 0, anchorY: -4, display: "ALWAYS",
  });
  const first = ps.at(0);
  const last = ps.at(-1);
  if (first) {
    out.push({ id: 1, latitude: first.lat, longitude: first.lng, width: 1, height: 1,
      callout: label(props.trace.route?.from || first.name, "#8C8C8C") });
  }
  if (last) {
    out.push({ id: 2, latitude: last.lat, longitude: last.lng, width: 1, height: 1,
      callout: label(props.trace.route?.cur || last.name, "#C8302B") });
  }
  return out;
});

/** 地图中心取折线中点：取当前位置当中心时，起点常常被挤出视野 */
const center = computed(() => {
  const ps = points.value;
  const lat = ps.reduce((s, p) => s + p.lat, 0) / ps.length;
  const lng = ps.reduce((s, p) => s + p.lng, 0) / ps.length;
  return { lat, lng };
});

/**
 * 从节点文案里抽快递员电话做成可拨。**抽不到就保持纯文本，不猜** ——
 * 承运商文案里电话位置不固定，猜错的下场是买家拨给了投诉热线。
 *
 * <p><b>这里的变量名不能叫 m</b>：UnoCSS 把 {@code m[0]} 读成 margin 工具类的任意值
 * （等价于 {@code m-[0]}），于是在 script 里改写源码，整个 .vue 编译失败 ——
 * 报错是 magic-string 的「Cannot split a chunk」并指向第 1 行，而 vue-tsc 全绿。
 * 凡是工具类前缀（m / p / w / h / text / bg…）都别拿来当紧跟方括号的变量名。
 */
function phoneIn(text: string): string | null {
  const hit = /1[3-9]\d{9}/.exec(text || "");
  return hit === null ? null : hit[0];
}

/**
 * 时间只到「月-日 时:分」。带上年份与秒，这一列就要占掉近一半行宽，
 * 把节点文案挤成每行三四个字 —— 而年份与秒对「到哪了」没有任何信息量。
 */
function at(ms: number): string {
  const d = new Date(ms);
  const two = (v: number) => String(v).padStart(2, "0");
  return `${two(d.getMonth() + 1)}-${two(d.getDate())} ${two(d.getHours())}:${two(d.getMinutes())}`;
}

function call(phone: string) {
  uni.makePhoneCall({ phoneNumber: phone });
}

function toggle() {
  expanded.value = !expanded.value;
}

const emit = defineEmits<{ openWx: [] }>();
</script>

<template>
  <view class="shtrace">
    <!-- 微信渠道：详情交给微信，我们这边只留步骤条与最新一条 —— 买家最常问的那句不该要多点一次才看得到 -->
    <template v-if="trace.displayMode === 'wx-plugin'">
      <view v-if="showSteps" class="shtrace__steps">
        <view v-for="(k, i) in stepKeys" :key="k" class="shtrace__sp" :class="{ 'is-done': i < stepIndex, 'is-now': i === stepIndex }">
          <view class="shtrace__dot"></view>
          <text class="txt-caption shtrace__lb">{{ $t(`trace.step.${k}`) }}</text>
        </view>
      </view>
      <view v-if="trace.atLocker" class="sh-row shtrace__locker">
        <text class="txt-body sh-fill">{{ $t("trace.atLocker") }}</text>
      </view>
      <view v-if="latestText" class="sh-row shtrace__latest">
        <text class="txt-body sh-fill">{{ latestText }}</text>
      </view>
      <view class="sh-btn sh-btn--soft shtrace__wx" @tap="emit('openWx')">{{ $t("trace.openWx") }}</view>
    </template>

    <!-- 自建渠道：地图 + 步骤条 + 时间线 -->
    <template v-else>
      <view v-if="trace.atLocker" class="sh-row shtrace__locker">
        <text class="txt-body sh-fill">{{ $t("trace.atLocker") }}</text>
      </view>
      <view v-if="hasMap" class="shtrace__map">
        <map
          class="shtrace__mapbox"
          :latitude="center.lat"
          :longitude="center.lng"
          :scale="5"
          :markers="markers"
          :polyline="polyline"
          :show-location="false"
          :enable-scroll="false"
          :enable-zoom="false"
        ></map>
        <text class="txt-caption shtrace__cap">{{ $t("trace.cityLevel") }}</text>
      </view>

      <view v-if="showSteps" class="shtrace__steps">
        <view v-for="(k, i) in stepKeys" :key="k" class="shtrace__sp" :class="{ 'is-done': i < stepIndex, 'is-now': i === stepIndex }">
          <view class="shtrace__dot"></view>
          <text class="txt-caption shtrace__lb">{{ $t(`trace.step.${k}`) }}</text>
        </view>
      </view>

      <view class="shtrace__list">
        <view v-for="(n, i) in shown" :key="i" class="sh-row sh-row--top shtrace__node">
          <text class="txt-caption sh-muted shtrace__at sh-num">{{ $t("trace.at", { t: at(n.at) }) }}</text>
          <view class="sh-fill">
            <text class="txt-caption shtrace__text" :class="{ 'txt-strong': i === 0 && expanded === false }">{{ n.text }}</text>
            <text v-if="n.location" class="txt-caption sh-muted shtrace__loc">{{ n.location }}</text>
            <text v-if="phoneIn(n.text)" class="txt-caption txt-primary shtrace__tel" @tap="call(String(phoneIn(n.text)))">
              {{ $t("trace.callCourier") }}
            </text>
          </view>
        </view>
      </view>
      <text v-if="foldable" class="txt-caption txt-primary shtrace__more" @tap="toggle">
        {{ expanded ? $t("trace.fold") : $t("trace.unfold", { n: trace.nodes.length }) }}
      </text>
    </template>
  </view>
</template>

<style scoped>
.shtrace {
  display: flex;
  flex-direction: column;
}
.shtrace__map {
  position: relative;
  margin-bottom: 24rpx;
}
.shtrace__mapbox {
  width: 100%;
  height: 280rpx;
  border-radius: 24rpx;
  /* 底图没加载出来时（H5 没配腾讯地图 key）别留一块纯白的洞，看着像渲染失败 */
  background: var(--sh-bg);
}
.shtrace__cap {
  position: absolute;
  left: 16rpx;
  bottom: 12rpx;
  color: var(--sh-sub);
  background: var(--sh-surface);
  border-radius: 16rpx;
  padding: 4rpx 12rpx;
}
.shtrace__steps {
  display: flex;
  margin-bottom: 24rpx;
}
.shtrace__sp {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
}
.shtrace__dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
  background: var(--sh-faint);
  margin-bottom: 8rpx;
}
.shtrace__sp.is-done .shtrace__dot {
  background: var(--sh-success);
}
/*
 * 当前这一档要一眼认出来。只靠颜色不行：已完成用 success、当前用 primary，
 * 本皮肤下两个都是绿，挨在一起几乎分不出 —— 所以当前档再放大一圈并加外环。
 */
.shtrace__sp.is-now .shtrace__dot {
  background: var(--sh-primary);
  width: 24rpx;
  height: 24rpx;
  margin-bottom: 4rpx;
  box-shadow: 0 0 0 6rpx var(--sh-primary-tint);
}
.shtrace__lb {
  color: var(--sh-sub);
}
.shtrace__sp.is-done .shtrace__lb {
  color: var(--sh-ink);
}
.shtrace__sp.is-now .shtrace__lb {
  color: var(--sh-primary-text);
}
.shtrace__locker,
.shtrace__latest {
  margin-bottom: 16rpx;
}
.shtrace__wx {
  margin-top: 8rpx;
}
.shtrace__list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.shtrace__node {
  gap: 16rpx;
}
.shtrace__at {
  flex-shrink: 0;
}
.shtrace__text {
  display: block;
  color: var(--sh-ink);
}
.shtrace__loc {
  display: block;
}
.shtrace__tel {
  display: block;
  margin-top: 4rpx;
}
.shtrace__more {
  display: block;
  text-align: center;
  margin-top: 16rpx;
}
</style>
