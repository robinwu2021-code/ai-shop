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

/**
 * ★ 这里**必须用 `uni.showModal`，不能用仓库通用的 `confirm()`**。
 *
 * 真机 0.5.61 上点「清空」：屏幕变暗（遮罩在了），**对话框一个字都看不见** ——
 * `confirm()` 是自绘的 view 弹层，而本页有原生 `<map>`，原生组件是独立图层、层级最高，
 * 自绘弹层被它整个盖住。商家看到的是「屏幕暗了、什么都点不动」，只能按返回键退出去。
 * 不报错、不白屏，和前面两处（地图不渲染、准星看不见）是同一个根：原生组件不吃 DOM 层级。
 * `showModal` 是系统原生弹窗，一定在最上层。
 */
function clearAll() {
  if (!points.value.length) return;
  uni.showModal({
    // 文案放 content 不放 title：原生弹窗的 title 是粗体短标题，只给 title 会在下面留一片空白
    content: t("store.polygonClearConfirm"),
    success: (res) => {
      if (res.confirm) {
        points.value = [];
      }
    },
  });
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
        >
          <!--
            ★ 准星必须是 `<cover-view>`，而且必须是 `<map>` 的**子节点**。
            真机 0.5.59 上用普通 `<view>` 叠在地图上：地图画出来了，**十字一点都看不见** ——
            原生组件在 App 端是独立图层、层级最高，普通 view 盖不住它，不报错也没有任何痕迹。
            样式写成内联：cover-view 是原生渲染的，对 scoped class 的支持各端不一。
          -->
          <cover-view class="cross">
            <cover-view class="cross__v"></cover-view>
            <cover-view class="cross__h"></cover-view>
          </cover-view>
        </map>
      </view>

      <view class="sh-row bar">
        <text class="txt-strong">{{ $t("store.polygonCount", { n: points.length }) }}</text>
        <!-- 顶点不足 3 个时说清差几个，而不是让「完成」灰着不解释 -->
        <text v-if="!canFinish" class="txt-caption sh-muted">{{ $t("store.polygonNeedThree") }}</text>
      </view>

      <view class="sh-row ops">
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
/*
 * ★ **地图高度必须是确定值，不能靠 flex:1 + height:100% 继承。**
 *
 * 2026-10-10 真机（Android 0.5.58）上整块地图没渲染：一片空白，准星还跑到了页面顶部。
 * 原因是 `<map>` 在 App 端是**原生组件**，它要在布局时就拿到确定高度；
 * 而 `height:100%` 要一路继承到根，中间只要有一级没有确定高度，链条就断 ——
 * 原生组件拿到 0 高度，整块不画，**不报错、不白屏，就是没有**。H5 上反而正常（DOM 元素不挑这个），
 * 所以只有真机看得见（与「模拟器不是真机」同一类）。
 * 用 vh 给一个自足的高度，不依赖任何父级。
 */
.wrap {
  display: flex;
  flex-direction: column;
}
.mapbox {
  position: relative;
  height: 62vh;
}
.mapbox__m {
  width: 100%;
  height: 100%;
}
/*
 * 准星：铺满地图的一层，十字靠两条线各自 50% 定位 ——
 * 不用 transform 也不用负 margin（cover-view 对两者的支持各端不一，而偏 1rpx 肉眼看不出）。
 */
.cross {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
.cross__v,
.cross__h {
  position: absolute;
  background: var(--sh-primary);
}
/* 居中用 translate 而不是 -1rpx 负 margin：半格值不在 4rpx 网格上，且 2rpx 线的一半本就不是整格 */
/*
 * 线长用**百分比**，不用 calc 也不用 transform —— cover-view 是原生渲染的，
 * 对这两者的支持各端不一（0.5.60 真机上线长写 100% 就成了贯穿全图的两条红线，
 * 看着像地图自己的分区线，而不是一个取景准星）。
 * 48% 起、长 4%，中心正好落在 50%。
 */
.cross__v {
  left: 50%;
  top: 48%;
  width: 2rpx;
  height: 4%;
}
.cross__h {
  top: 50%;
  left: 48%;
  height: 2rpx;
  width: 4%;
}
/* 横排与弱化色都走库件（.sh-row / .sh-muted），这里只留本页特有的内边距 */
.bar {
  padding: 12rpx 24rpx;
}
.ops {
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
