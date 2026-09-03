<script setup lang="ts">
// 选择收货地址：**这一页存在的全部理由是让地址带上坐标。**
//
// 手打出来的地址只是一串字：商家的自送半径判不了（后端那条闸明写着「没坐标就放行」）、
// 骑手导航打不开、按坐标算可见性也推不出任何商家 —— 而这四件事在页面上**看不出区别**，
// 所以它一直没被当成问题。这一页把「选」提为主路，「打」降为兜底。
//
// 每一段都自检：这个端给不了的整段不显示，而不是给一个点了没反应的入口。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { canSearchPlaces, searchPlacesNearNative, searchPlacesNative } from "@shared/ports/geo-search";
import type { PlaceHit } from "@shared/ports/geo-search";
import { canChooseLocation, chooseLocation, getLocationDetailed } from "@shared/ports/location";
import { distance as fmtDistance } from "@shared/utils/format";
import { pickedPlace, placeFrom } from "@/shared/address-pick";
import type { Community } from "@shared/types";

const { t } = useI18n();

/** 这个端支不支持原生地点搜索。H5 / 小程序没有 plus，整个搜索段不显示 */
const canSearch = canSearchPlaces();
/** 地图选点。H5 没配 JS key —— 提前问，别等点下去才弹「不支持」 */
const canMap = canChooseLocation();

const keyword = ref("");
const hits = ref<PlaceHit[]>([]);
const searching = ref(false);

/** 这一趟的定位。null = 没拿到，那不是错误：首屏本来就不许依赖静默精确定位 */
const at = ref<{ lat: number; lng: number } | null>(null);
/**
 * 坐标是不是模糊的（区级，误差约 5 公里）。
 * 是的话**不许显示距离** —— 「733m」看着确凿，实际误差比它本身还大，
 * 而用户会照着它挑最近的那个。排序错了只是顺序不理想，写出假数字是骗人。
 */
const coarse = ref(false);
const locating = ref(false);
const nearby = ref<Community[]>([]);

/** 拿到坐标之前，「当前定位」那一段不占位置 */
const hasHere = computed(() => at.value !== null);

/**
 * 附近里**能用的**那些。没坐标的社区选了等于又得到一条没坐标的地址，这一页就白来了。
 *
 * <p><b>过滤要在这里、不在模板的行上。</b>放在行上时整段的 `v-if` 看的还是原始条数，
 * 于是「附近」这张卡片会带着标题渲染出来、底下一条没有 —— 一个承诺了内容的空标题。
 * 实测就是这样：mock 的社区都没坐标，守卫全绿，页面上是个空壳。
 */
const nearbyPickable = computed(() =>
  nearby.value.filter((c) => c.latE6 != null && c.lngE6 != null),
);

/**
 * 定位失败时说哪一句。**只说这个端真有的路** ——
 * H5 既没有原生搜索也没配地图 key，对着他说「可以直接搜索、在地图上选点」
 * 是在许诺屏幕上根本不存在的两个东西。
 */
const locateFailedKey = computed(() =>
  canSearch || canMap ? "addressPick.locateFailed" : "addressPick.locateFailedManualOnly",
);

async function locate() {
  locating.value = true;
  try {
    const r = await getLocationDetailed();
    at.value = r.ok ? { lat: r.coords.lat, lng: r.coords.lng } : null;
    coarse.value = r.ok && r.fuzzy === true;
    if (at.value) {
      // 复用「附近已开通社区」——它本来就是按坐标查的，且带 name / address / 坐标
      nearby.value = await api.nearbyCommunities(at.value.lat, at.value.lng).catch(() => []);
    }
  } finally {
    locating.value = false;
  }
}

/**
 * 逐字联想。**丢弃过期结果由 port 自己做**（它是实例级回调，见 geo-search 的说明），
 * 这里只管别把每一次按键都打出去。
 */
let timer: ReturnType<typeof setTimeout> | null = null;
function onKeyword() {
  if (timer) clearTimeout(timer);
  const kw = keyword.value.trim();
  if (!kw) {
    hits.value = [];
    return;
  }
  timer = setTimeout(() => void runSearch(kw), 300);
}

async function runSearch(kw: string) {
  searching.value = true;
  try {
    /*
     * 有坐标就在坐标周围搜。**这是「输名字找小区」的正确形状** ——
     * 按城市搜时 city 只是偏好不是约束，在深圳搜「福安」会返回福建的福安市
     * （geo-search 里记着这条实测）。围着当前位置几公里搜，真小区才排得进来。
     */
    const r = at.value
      ? await searchPlacesNearNative(kw, at.value)
      : await searchPlacesNative(kw);
    hits.value = r ?? [];
  } finally {
    searching.value = false;
  }
}

/** 交回去并返回。三种来源都收敛到 placeFrom，省市区的拆分只有一处 */
function choose(p: { name?: string; address?: string; lat: number; lng: number }) {
  pickedPlace.offer(placeFrom(p));
  uni.navigateBack();
}

function chooseHit(h: PlaceHit) {
  choose({ name: h.name, address: h.address, lat: h.lat, lng: h.lng });
}

function chooseCommunity(c: Community) {
  if (c.latE6 == null || c.lngE6 == null) return; // 没坐标的不列，见模板里的 v-if
  choose({ name: c.name, address: c.address, lat: c.latE6 / 1e6, lng: c.lngE6 / 1e6 });
}

function chooseHere() {
  if (!at.value) return;
  choose({ name: "", address: "", lat: at.value.lat, lng: at.value.lng });
}

async function onMap() {
  const r = await chooseLocation(at.value);
  if (!r.ok) {
    if (r.reason === "unsupported") uni.showToast({ title: String(t("address.mapUnsupported")), icon: "none" });
    return;
  }
  choose(r.picked);
}

/**
 * 「都搜不到，我自己打」。**要显式交回一个 manual**，不能只是 navigateBack ——
 * 那与「用户点了系统返回」分不开，而那两种情况该做的事正好相反。
 */
function manual() {
  pickedPlace.offer({ kind: "manual" });
  uni.navigateBack();
}

onLoad(() => {
  void locate();
});
</script>

<template>
  <sh-scaffold title-key="addressPick.title">
    <view v-if="canSearch" class="sh-card searchbox">
      <input
        v-model="keyword"
        class="field__input"
        maxlength="32"
        :placeholder="$t('addressPick.searchPh')"
        @input="onKeyword"
      />
    </view>

    <!-- 有关键词时结果顶掉「附近」：别让用户在两份列表里找自己刚搜的那个 -->
    <view v-if="keyword.trim()" class="sh-card block">
      <text class="txt-sub block__title">{{ $t("addressPick.results") }}</text>
      <view v-for="(h, i) in hits" :key="`${h.name}-${i}`" class="row" @tap="chooseHit(h)">
        <text class="txt-body row__name">{{ h.name }}</text>
        <text class="txt-caption row__sub">{{ h.address }}</text>
      </view>
      <text v-if="searching" class="txt-caption block__empty">{{ $t("addressPick.searching") }}</text>
      <text v-else-if="!hits.length" class="txt-caption block__empty">
        {{ $t("addressPick.noResults") }}
      </text>
    </view>

    <template v-else>
      <view v-if="hasHere" class="sh-card block">
        <view class="sh-row sh-row--between">
          <text class="txt-sub block__title">{{ $t("addressPick.here") }}</text>
          <text class="txt-caption txt-primary" @tap="locate">{{ $t("addressPick.relocate") }}</text>
        </view>
        <!-- 模糊定位时不显示距离，理由见 script 里 coarse 那段 -->
        <text v-if="coarse" class="sh-hint">{{ $t("addressPick.coarseHint") }}</text>
        <view class="row" @tap="chooseHere">
          <text class="txt-body row__name">{{ $t("addressPick.useHere") }}</text>
        </view>
      </view>
      <text v-else-if="!locating" class="sh-hint">{{ $t(locateFailedKey) }}</text>

      <!-- 判的是「能用的有几条」，不是「拿回来几条」—— 见 nearbyPickable 那段 -->
      <view v-if="nearbyPickable.length" class="sh-card block">
        <text class="txt-sub block__title">{{ $t("addressPick.nearby") }}</text>
        <view v-for="c in nearbyPickable" :key="c.communityNo" class="row" @tap="chooseCommunity(c)">
          <text class="txt-body row__name">{{ c.name }}</text>
          <text class="txt-caption row__sub">
            {{ c.address }}<text v-if="!coarse && c.distance"> · {{ fmtDistance(c.distance) }}</text>
          </text>
        </view>
      </view>
    </template>

    <view class="sh-card block">
      <view v-if="canMap" class="row" @tap="onMap">
        <text class="txt-body row__name txt-primary">{{ $t("addressPick.onMap") }}</text>
      </view>
      <view class="row" @tap="manual">
        <text class="txt-body row__name">{{ $t("addressPick.manual") }}</text>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.searchbox {
  margin-bottom: 20rpx;
}
.block {
  margin-bottom: 20rpx;
}
.block__title {
  display: block;
  margin-bottom: 8rpx;
}
.block__empty {
  display: block;
  padding: 24rpx 0;
  text-align: center;
}
.row {
  padding: 20rpx 0;
  border-top: 1rpx solid var(--sh-line);
}
/* 卡片里第一行上面不画线：那条线是用来分隔两行的，画在最上面只是一道多余的横杠 */
.row:first-child {
  border-top: none;
}
.row__name {
  display: block;
}
.row__sub {
  display: block;
  margin-top: 6rpx;
}
</style>
