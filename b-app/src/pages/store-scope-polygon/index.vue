<script setup lang="ts">
/**
 * 画配送范围（ADR-034 · TDD-可见范围分级匹配与多边形 T14）。
 *
 * <h2>为什么是独立页，不是弹层</h2>
 * uni 的 {@code <map>} 在 App 端是**原生组件**。把它放进 fixed 定位的弹层里，
 * 整棵子树都不渲染 —— 不报错、不白屏，就是一片空白（2026-10-09 在物流轨迹上踩过：
 * 弹层里画不出地图，排查方向一路指向经纬度丢失，实际是原生组件的层级问题）。
 * 所以画图单独占一页。
 *
 * <h2>为什么用「十字准星 + 取中心点」而不是点地图加点</h2>
 * {@code <map>} 的 {@code @tap} 事件**两端行为不一致**：小程序给 detail 里的经纬度，
 * App 端（高德 SDK）不保证给。靠它加点会出现「小程序能画、真机点不动」这种
 * 只有真机才看得见的裂口。改成：商家拖动地图把准星对准要加的位置，点「加点」，
 * 用 {@code getCenterLocation} 取当前中心 —— 这个 API 两端都稳。
 * 代价是一次只能加一个点，而画配送边界本来就是逐点围一圈。
 *
 * <h2>回传</h2>
 * 顶点串可能上千字符，塞 URL query 会被截断，而截断后的 JSON 解析失败
 * 只表现成「画了半天保存上去是空的」。所以走 {@code uni.$emit}。
 */
import { computed, onMounted, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();

/** E6 整数顶点 [lngE6, latE6]，与全库口径一致 */
const points = ref<[number, number][]>([]);
/**
 * 地图中心（度）。三级回退：上一页带过来的门店坐标 → 当前定位 → 一个确定的点。
 * 最后那一级不是「猜」，是「总得有个起点」——商家拖两下就到了，而没有起点地图起不来。
 */
const center = ref({ lat: 22.54, lng: 114.06 });
const mapCtx = ref<UniApp.MapContext | null>(null);
const busy = ref(false);

/** 顶点 ≥3 才是一个面 */
const canFinish = computed(() => points.value.length >= 3);

/** 画给 <map> 的多边形。顶点不足 3 个时不画面，只画已落的点 */
const polygons = computed(() => {
  if (points.value.length < 3) return [];
  return [{
    points: points.value.map(([lng, lat]) => ({ latitude: lat / 1e6, longitude: lng / 1e6 })),
    fillColor: "#2F80ED33",
    strokeColor: "#2F80ED",
    strokeWidth: 2,
  }];
});

/** 已落的点逐个标出来，否则不足 3 个时商家看不出自己点了几下 */
const markers = computed(() =>
  points.value.map(([lng, lat], i) => ({
    id: i,
    latitude: lat / 1e6,
    longitude: lng / 1e6,
    width: 12,
    height: 12,
    callout: { content: String(i + 1), display: "ALWAYS", fontSize: 10, padding: 4, borderRadius: 6 },
  })),
);

/** 上一页带过来的门店坐标优先 */
const fromQuery = ref(false);
onLoad((q?: Record<string, string>) => {
  const lat = Number(q?.latE6);
  const lng = Number(q?.lngE6);
  if (Number.isFinite(lat) && Number.isFinite(lng) && !(lat === 0 && lng === 0)) {
    center.value = { lat: lat / 1e6, lng: lng / 1e6 };
    fromQuery.value = true;
  }
});

onMounted(() => {
  mapCtx.value = uni.createMapContext("scopeMap");
  if (fromQuery.value) return;
  // 没带门店坐标就用当前定位；拿不到就留着那个固定起点，不提示——这不是故障，拖一下就好
  uni.getLocation({
    type: "gcj02",
    success: (res) => { center.value = { lat: res.latitude, lng: res.longitude }; },
    fail: () => {},
  });
});

/** 取地图当前中心作为一个顶点。两端都稳的那个 API */
function addPoint() {
  if (busy.value || !mapCtx.value) return;
  busy.value = true;
  mapCtx.value.getCenterLocation({
    success: (res: { latitude: number; longitude: number }) => {
      points.value = [
        ...points.value,
        [Math.round(res.longitude * 1e6), Math.round(res.latitude * 1e6)],
      ];
    },
    /*
     * 取不到中心点就说明地图没就绪（或权限没给）。**必须说出来** ——
     * 静默失败的表现是「点了加点没反应」，而商家会反复点，然后放弃。
     */
    fail: () => uni.showToast({ title: t("store.polygonPointFailed"), icon: "none" }),
    complete: () => { busy.value = false; },
  });
}

function undo() {
  points.value = points.value.slice(0, -1);
}

async function clearAll() {
  if (!points.value.length) return;
  if (!(await confirm({ title: t("store.polygonClearConfirm") }))) return;
  points.value = [];
}

function finish() {
  if (!canFinish.value) return;
  uni.$emit("store-scope:polygon", JSON.stringify(points.value));
  uni.navigateBack();
}

function back() {
  uni.navigateBack();
}
</script>

<template>
  <sh-scaffold :title-key="'store.drawArea'" @back="back">
    <view class="wrap">
      <!--
        地图占满可视区，准星固定在正中 —— 它不是地图的一部分，是叠在上面的一个十字。
        拖地图让准星落到要加的位置，点下面的「加点」。
      -->
      <view class="mapbox">
        <map
          id="scopeMap"
          class="mapbox__m"
          :latitude="center.lat"
          :longitude="center.lng"
          :scale="15"
          :markers="markers"
          :polygons="polygons"
          :show-location="true"
        ></map>
        <view class="cross">
          <view class="cross__v"></view>
          <view class="cross__h"></view>
        </view>
      </view>

      <view class="bar">
        <text class="txt-caption bar__n">{{ $t("store.polygonCount", { n: points.length }) }}</text>
        <!-- 顶点不足 3 个时说清差几个，而不是让「完成」灰着不解释 -->
        <text v-if="!canFinish" class="txt-caption bar__hint">{{ $t("store.polygonNeedThree") }}</text>
      </view>

      <view class="ops">
        <view class="sh-chip" :class="{ 'is-off': !points.length }" @tap="undo">{{ $t("store.polygonUndo") }}</view>
        <view class="sh-chip" :class="{ 'is-off': !points.length }" @tap="clearAll">{{ $t("store.polygonClear") }}</view>
        <view class="sh-chip sh-chip--primary ops__add" @tap="addPoint">{{ $t("store.polygonAdd") }}</view>
        <view class="sh-chip sh-chip--primary" :class="{ 'is-off': !canFinish }" @tap="finish">
          {{ $t("common.done") }}
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.wrap {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.mapbox {
  position: relative;
  flex: 1;
  min-height: 0;
}
.mapbox__m {
  width: 100%;
  height: 100%;
}
/* 准星：叠在地图上的一个十字，不参与点击 */
.cross {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 48rpx;
  height: 48rpx;
  /* 居中用 translate，不用负 margin：margin-left 在阿语下不跟着翻（而这里要的是「正中」，与读写方向无关） */
  transform: translate(-50%, -50%);
  pointer-events: none;
}
.cross__v,
.cross__h {
  position: absolute;
  background: var(--sh-primary);
}
/* 居中用 translate 而不是 -1rpx 负 margin：半格值不在 4rpx 网格上，且 2rpx 线的一半本就不是整格 */
.cross__v {
  left: 50%;
  top: 0;
  width: 2rpx;
  height: 100%;
  transform: translateX(-50%);
}
.cross__h {
  top: 50%;
  left: 0;
  height: 2rpx;
  width: 100%;
  transform: translateY(-50%);
}
.bar {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 12rpx 24rpx;
}
.bar__n {
  font-weight: 600;
}
.bar__hint {
  color: var(--sh-sub);
}
.ops {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 0 24rpx 24rpx;
}
.ops__add {
  /* 「加点」推到行尾：阿语下行尾在左边，所以用逻辑属性 */
  margin-inline-start: auto;
}
.is-off {
  opacity: 0.45;
}
</style>
