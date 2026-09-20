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
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { ROUTES } from "@shared/utils/constants";
import type { PlaceSearchHit } from "@shared/types";
import { canChooseLocation, chooseLocation } from "@shared/ports/location";
import { useLocationStore } from "@/stores/location";
import { distance as fmtDistance } from "@shared/utils/format";
import { pickedCity, pickedPlace, placeFrom } from "@/shared/address-pick";
import type { Address, Community } from "@shared/types";

const { t } = useI18n();
const location = useLocationStore();

/**
 * **搜索段永远显示。**
 *
 * <p>此前它判的是「这个端有没有原生高德 SDK」（`canSearchPlaces()`），
 * 于是 H5 与小程序上整段不渲染 —— 而那等于告诉用户「这儿什么都没有」。
 * 改走后端的统一搜索之后，任何端、甚至地图整个挂掉，它都至少搜得到
 * 我们自己库里的地方。
 */
const canSearch = true;
/** 地图选点。H5 没配 JS key —— 提前问，别等点下去才弹「不支持」 */
const canMap = canChooseLocation();

/**
 * 在**哪个城市**里搜。空 = 围着当前定位搜。
 *
 * <p>此前只有后者，于是人在深圳给北京的家填地址时搜「望京」什么也搜不到 ——
 * 而那不是一条报错，是一个空列表，他会以为那个地方不存在。
 *
 * <p>选了城市就**不再传坐标**：坐标在的话后端会围着坐标搜（那是对的默认），
 * 两个都传等于让后端猜他要哪个。
 */
/**
 * 选完往哪儿走。`edit` = 从「新增地址」过来，选完直接去新建页；
 * 空 = 从新建页的「重选」过来，原样返回。见 {@link choose}。
 */
let next = "";

/**
 * **浏览模式**（`?mode=browse`，首页顶栏点位置进来的）。TDD-C端首页位置选择。
 *
 * <p>同一页两个问题：默认那个是「这条收货地址填哪儿」，浏览模式是
 * 「现在按哪儿看货」。差别只在**选完干什么** —— 前者把地点交回给建地址那条流程，
 * 后者当场把浏览位置切过去（`useTransient` / `switchTo`），一条地址都不写。
 *
 * <p>没做成第二个页面：搜索、附近、地图选点这三段就是这一页，
 * 再写一份的话两页的搜索迟早给出两种结果，而界面上看不出来。
 */
const browse = ref(false);

const city = ref<{ code: string; name: string } | null>(null);

const keyword = ref("");
const hits = ref<PlaceSearchHit[]>([]);
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
/** 附近小区这次没取到。**与「这一带没有小区」是两件事** */
const failed = ref(false);
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
 * 「附近」最多给几条。
 *
 * <p>龙华一个区导进来 2783 个小区之后，同一个点周围几十米内就有三四个名字相近的
 * （景龙新邨东区 / 景龙新村 / 景龙新邨西区）。平铺十几条的结果不是「选择多」，
 * 是**认不出哪个是自己家** —— 而底下常驻着「地图选点」，在地图上点一下比
 * 翻十几个相近的名字容易得多。
 *
 * <p>所以截断，并且**把截断这件事说出来**（下面那行提示）——
 * 不说的话，住在第 6 近那个小区的人会以为这一带没有他家。
 */
const NEARBY_MAX = 5;
/**
 * **上面那张卡给过的那个，不在「附近」里再给一遍。**
 *
 * 实测（龙华，2783 个小区导入之后）：站在景龙新邨东区，
 * 「当前位置」写着它，「附近」第一条又是它 —— 而且因为距离是 0，
 * 那一行连距离都不显示，看起来像另一个同名的地方。
 * 同一个东西在一屏上出现两次，读的人要先判断"这俩是不是一回事"。
 */
const nearbyRest = computed(() =>
  nearbyPickable.value.filter((c) => c.name !== hereName.value),
);
const nearbyShown = computed(() => nearbyRest.value.slice(0, NEARBY_MAX));
const nearbyTruncated = computed(() => nearbyRest.value.length > NEARBY_MAX);

/**
 * 当前位置落在哪个聚落里。**这一页最关键的一次决定靠它** ——
 * 此前这张卡只有一行「使用当前位置」，不说在哪儿，用户无从判断该不该用。
 * 取不到就留空，界面回落到那一行原来的文字：宁可少一行，不要编一个地名。
 */
const hereName = ref("");
const hereAddress = ref("");

/**
 * 定位失败时说哪一句。**只说这个端真有的路** ——
 * H5 既没有原生搜索也没配地图 key，对着他说「可以直接搜索、在地图上选点」
 * 是在许诺屏幕上根本不存在的两个东西。
 */
const locateFailedKey = computed(() =>
  canSearch || canMap ? "addressPick.locateFailed" : "addressPick.locateFailedManualOnly",
);

/**
 * 取一次位置并把「附近」拉回来。
 *
 * <p>**坐标与地名走 store 的单一真源**（`ensureHere`）—— 这一页此前自己定位、
 * 自己解析，而首页与收货地址页各有另一套。三处迟早给出三个答案，
 * 实测截图里就出现过两页说的不是同一个地方。
 *
 * @param force 用户点了「重新定位」
 */
async function locate(force = false) {
  locating.value = true;
  try {
    const here = force ? await location.relocate() : await location.ensureHere();
    at.value = here ? here.coords : null;
    coarse.value = here?.coarse === true;
    if (at.value) {
      // 复用「附近已开通社区」——它本来就是按坐标查的，且带 name / address / 坐标。
      // **不兜底**：兜成空之后下面那个 `v-if="nearbyPickable.length"`
      // 让整块「附近的小区」消失 —— 顾客看到的不是「没取到」，是「这一带没有小区」
      try {
        nearby.value = await api.nearbyCommunities(at.value.lat, at.value.lng);
        failed.value = false;
        /*
         * 顺带把「我在哪」取出来给上面那张卡。**复用已经拿回来的那一批** ——
         * 围栏命中的那个就在 nearby 里，再发一次 resolve 是白花的往返。
         * 一个都没命中时留空（新城区），卡上回落到原来那行文字。
         */
        /*
         * 地名同样来自单一真源。**围栏命中时优先用「附近」里那一条的地址** ——
         * 它带着更完整的门牌，而 store 里那个只有名字。
         */
        // 模糊定位只说到区（location.hereName），也不给门牌 —— 那条门牌属于偏移点旁的楼盘
        const hit = here?.coarse ? undefined : nearby.value.find((c) => c.name === here?.place?.name);
        hereName.value = location.hereName;
        hereAddress.value = here?.coarse ? "" : hit?.address ?? here?.place?.address ?? "";
      } catch {
        failed.value = true;
      }
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
     * **走后端那一条，不再只走端上的原生 SDK。**
     *
     * 后端把「已开通聚落 + 我们自己的地名库 + 地图」合并过，而且**本地优先**：
     * 本地那条带着 communityNo，选中它才能直接绑聚落。原生 SDK 只有名字与坐标。
     *
     * 更要紧的是**地图挂了它照样有结果**（只是少）—— 端上此前是整段搜索不渲染，
     * 而那等于告诉用户「这儿什么都没有」，一个搜不到东西的空列表比那强得多。
     *
     * 有坐标就围着坐标搜：按城市搜时 city 只是偏好不是约束，
     * 在深圳搜「福安」会返回福建的福安市（geo-search 里记着这条实测）。
     */
    const r = await api.searchPlaces(
      kw,
      // 选了城市就不传坐标：两个都传等于让后端猜他要哪个
      city.value || !at.value ? undefined : Math.round(at.value.lat * 1e6),
      city.value || !at.value ? undefined : Math.round(at.value.lng * 1e6),
      city.value?.name,
    ).catch(() => [] as PlaceSearchHit[]);
    hits.value = r;
  } finally {
    searching.value = false;
  }
}

/** 交回去并返回。三种来源都收敛到 placeFrom，省市区的拆分只有一处 */
/**
 * 交回去并离开。
 *
 * <p><b>去哪儿取决于是谁打开的这一页</b>：
 * <ul>
 *   <li>`next=edit`（从收货地址页的「新增」过来）→ <b>直接 redirect 去新建页</b>。
 *       此前是 `navigateBack` 回列表、由列表再弹去新建 —— 而列表那段判断用的是
 *       非消费的 `peek`，信箱里留着东西时它每次显示都再弹一次，
 *       用户从新建页返回刚落到列表又被弹走，看起来就是「自动跳到了别的页面」。</li>
 *   <li>没有 `next`（从新建页的「重选」过来）→ 原样返回，草稿还在那儿。</li>
 * </ul>
 *
 * <p>`redirectTo` 而不是 `navigateTo`：这一页的任务到此为止，
 * 留在栈上的话用户从新建页往回退会又看到它一次。
 */
async function choose(p: { name?: string; address?: string; lat: number; lng: number }) {
  /*
   * **浏览模式在这儿分叉。** 选中的那个点当场变成「现在按哪儿看货」，
   * 不进地址簿 —— 地址簿上限 20 条，逛一次存一条很快就满，
   * 而且它是上下文不是资料（PRD §6.1.0，`useTransient` 的说明）。
   */
  if (browse.value) {
    await location.useTransient({ lat: p.lat, lng: p.lng });
    uni.navigateBack();
    return;
  }
  pickedPlace.offer(placeFrom(p));
  if (next === "edit") {
    uni.redirectTo({ url: ROUTES.addressEdit });
    return;
  }
  uni.navigateBack();
}

function chooseHit(h: PlaceSearchHit) {
  if (h.latE6 == null || h.lngE6 == null) return;   // 没坐标的不列，见模板
  void choose({ name: h.name, address: h.address ?? "", lat: h.latE6 / 1e6, lng: h.lngE6 / 1e6 });
}

function chooseCommunity(c: Community) {
  if (c.latE6 == null || c.lngE6 == null) return; // 没坐标的不列，见模板里的 v-if
  void choose({ name: c.name, address: c.address, lat: c.latE6 / 1e6, lng: c.lngE6 / 1e6 });
}

function chooseHere() {
  if (!at.value) return;
  void choose({ name: "", address: "", lat: at.value.lat, lng: at.value.lng });
}

/**
 * 浏览模式里点一条收货地址：**切生效地址**，不是「选中一个地点」。
 *
 * <p>与上面那条路刻意不同 —— 他点的是一条**存过的**地址，
 * 那是一个明确的长期偏好，`switchTo` 会把「这次逛的临时位置」一并清掉，
 * 否则顶栏挂着「当前位置 · XX」而货已经按这条地址换过了。
 */
async function pickAddress(a: Address) {
  await location.switchTo(a.addressId).catch(() => null);
  uni.navigateBack();
}

/** 浏览模式底部那颗出口：去新建收货地址。**它是出口不是关卡** —— 没有地址照样能逛 */
function addAddress() {
  uni.navigateTo({ url: ROUTES.addressEdit });
}

async function onMap() {
  const r = await chooseLocation(at.value);
  if (!r.ok) {
    if (r.reason === "unsupported") uni.showToast({ title: String(t("address.mapUnsupported")), icon: "none" });
    return;
  }
  await choose(r.picked);
}

/**
 * 「都搜不到，我自己打」。**要显式交回一个 manual**，不能只是 navigateBack ——
 * 那与「用户点了系统返回」分不开，而那两种情况该做的事正好相反。
 */
/** 去选城市。回来时 onShow 取信箱 */
function gotoCity() {
  uni.navigateTo({ url: ROUTES.cityPick });
}

function manual() {
  pickedPlace.offer({ kind: "manual" });
  if (next === "edit") {
    uni.redirectTo({ url: ROUTES.addressEdit });
    return;
  }
  uni.navigateBack();
}

/**
 * 从城市选择页回来。**取到就重搜一次** —— 不重搜的话他换了城市却看到上一个
 * 城市的结果，而列表本身没有任何地方写着它是哪儿的。
 */
onShow(() => {
  /*
   * **浏览模式每次显示都重拉一次地址簿。**
   *
   * 只在 onLoad 拉的话，从「新增收货地址」返回时**刚存的那条不出现** ——
   * 用户会以为没存上，回去再存一遍。收货地址页当年就是这么坏的（见那一页的 onShow），
   * 而这一页自己又踩了一次：2026-09-20 真机上建完地址返回，这一段整块还是不显示。
   */
  if (browse.value) void location.load();
  const c = pickedCity.take();
  if (!c) return;
  city.value = c;
  if (keyword.value.trim()) void runSearch(keyword.value.trim());
});

onLoad((q?: Record<string, string>) => {
  /*
   * `useHere=1`：从收货地址页那颗「存为收货地址」进来的。
   *
   * **为什么绕这一道而不是在那边直接解析**：把坐标变成一条带省市区的地址，
   * 逻辑全在这一页的 `choose()` 里。在地址页再写一份，两处迟早给出不一样的
   * 省市区拆法 —— 而那种不一致在界面上看不出来，只会让「按区派单」偶尔落错。
   * 对用户仍然是一次点击：这一页只是过一下，定位拿到就自己交回去。
   */
    next = q?.next ?? "";
  browse.value = q?.mode === "browse";
  // 浏览模式要把地址簿摆出来；默认模式不需要，别白发一次请求
  if (browse.value) void location.load();
  const auto = q?.useHere === "1";
  void locate().then(() => {
    if (auto && at.value) chooseHere();
  });
});
</script>

<template>
  <sh-scaffold :title-key="browse ? 'addressPick.browseTitle' : 'addressPick.title'">
    <!--
      **在哪个城市里搜**。默认跟着定位走，点一下能换 ——
      「给父母下单」「出差前囤货」在这个品类里是真实高频，
      而那时他要填的地址不在他站着的城市。
    -->
    <view class="sh-card cityrow sh-row sh-row--between" @tap="gotoCity">
      <text class="txt-caption">{{ $t("addressPick.searchIn") }}</text>
      <text class="txt-body sh-fill cityrow__name">{{ city?.name || location.hereName || $t("addressPick.nearHere") }}</text>
      <text class="txt-caption txt-primary">{{ $t("addressPick.changeCity") }}</text>
    </view>

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
      <text class="txt-strong block__title">{{ $t("addressPick.results") }}</text>
      <view v-for="(h, i) in hits" :key="`${h.name}-${i}`" class="sh-row--divided" @tap="chooseHit(h)">
        <text class="txt-body row__name">{{ h.name }}</text>
        <text class="txt-caption row__sub">{{ h.address }}</text>
      </view>
      <!--
        **搜不到才给「手动填写」，而且只在这儿给。**
        它是这一页的最后一条出路，常驻在底部时会被读成与「地图选点」平级的
        另一种选法 —— 而两者的产出不一样（一条带坐标、一条不带），
        让买家去选一件他看不见后果的事。
      -->
      <view v-if="!searching && !hits.length" class="sh-center noresult" @tap="manual">
        <text class="txt-caption txt-primary">{{ $t("addressPick.manual") }}</text>
      </view>
      <text v-if="searching" class="txt-caption block__empty">{{ $t("addressPick.searching") }}</text>
      <text v-else-if="!hits.length" class="txt-caption block__empty">
        {{ $t("addressPick.noResults") }}
      </text>
    </view>

    <template v-else>
      <view v-if="hasHere" class="sh-card block">
        <view class="sh-row sh-row--between">
          <text class="txt-strong block__title">{{ $t("addressPick.here") }}</text>
          <text class="txt-caption txt-primary" @tap="locate(true)">{{ $t("addressPick.relocate") }}</text>
        </view>
        <!-- 模糊定位时不显示距离，理由见 script 里 coarse 那段 -->
        <text v-if="coarse" class="sh-hint">{{ $t("addressPick.coarseHint") }}</text>
        <!--
          **把「在哪儿」说出来。** 此前这一行只有「使用当前位置」五个字 ——
          而用户在这一页要做的正是「这个位置对不对」这个判断，不给地名他判不了。
          取不到地名时回落到原来那行文字（新城区、定位刚好落在围栏之外）。
        -->
        <view class="sh-row sh-row--divided hererow" @tap="chooseHere">
          <view class="sh-fill hererow__body">
            <text class="txt-body row__name">{{ hereName || $t("addressPick.useHere") }}</text>
            <text v-if="hereAddress" class="txt-caption row__sub">{{ hereAddress }}</text>
          </view>
          <text v-if="hereName" class="txt-caption txt-primary hererow__use">
            {{ $t("addressPick.useShort") }}
          </text>
        </view>
      </view>
      <text v-else-if="!locating" class="sh-hint">{{ $t(locateFailedKey) }}</text>

      <!--
        **我的收货地址** —— 只在浏览模式给（原型 l02）。
        默认模式这一页是在「造一条地址」，把地址簿摆出来等于让人在建地址时去挑一条已有的。

        一条都没有时整段不渲染（l06）：新用户先逛起来，底下那颗按钮才是他的下一步。
      -->
      <view v-if="browse && location.list.length" class="sh-card block">
        <text class="txt-strong block__title">{{ $t("addressPick.myAddresses") }}</text>
        <view
          v-for="a in location.list"
          :key="a.addressId"
          class="sh-row sh-row--divided"
          @tap="pickAddress(a)"
        >
          <view class="sh-fill nb__body">
            <text class="txt-body row__name">{{ a.tag || a.name }}</text>
            <text class="txt-caption row__sub">{{ a.region }}{{ a.detail }}</text>
          </view>
          <sh-icon
            v-if="a.addressId === location.active?.addressId"
            name="check"
            :size="28"
            color="var(--sh-primary)"
          ></sh-icon>
        </view>
      </view>

      <!-- 判的是「能用的有几条」，不是「拿回来几条」—— 见 nearbyPickable 那段 -->
      <!-- `|| failed` 一起判：没取到时这一块留在原地说出来，而不是整块消失 -->
      <view v-if="nearbyPickable.length || failed" class="sh-card block">
        <text class="txt-strong block__title">{{ $t("addressPick.nearby") }}</text>
        <!-- **摆在标题之下**：它说的是「这一块的内容没取到」，
             放到标题上面会读成「这张卡整个没加载」，而卡里还有别的东西。
             第一版摆错了位置 —— 小程序截图上一眼看出来的（H5 上我没看这一块）。 -->
        <sh-empty v-if="failed" line failed @retry="locate"></sh-empty>
        <!-- 距离单独一列右对齐：它是这一屏用来比较的那个量，塞在地址行尾要一行行读 -->
        <view v-for="c in nearbyShown" :key="c.communityNo" class="sh-row sh-row--divided" @tap="chooseCommunity(c)">
          <view class="sh-fill nb__body">
            <text class="txt-body row__name">{{ c.name }}</text>
            <text class="txt-caption row__sub">{{ c.address }}</text>
          </view>
          <text v-if="!coarse && c.distance" class="txt-caption nb__dist">{{ fmtDistance(c.distance) }}</text>
        </view>
        <!--
          **截断要说出来。** 不说的话，住在第 6 近那个小区的人会以为这一带没有他家，
          而正确的下一步（地图选点）就在底下常驻着。
        -->
        <text v-if="nearbyTruncated" class="sh-hint">{{ $t("addressPick.moreOnMap") }}</text>
      </view>
    </template>

    <!--
      **只剩「地图选点」一条，而且降成次级动作。**
      此前这里是两颗并排的兜底按钮（地图选点 / 手动填写）——
      它们的产出不一样（一条带坐标、一条不带），而这个差别决定了
      商家自送半径判不判得了、骑手导航打不打得开，**页面上看不出任何区别**。
      把这个选择丢给买家，等于让他替我们决定一件他看不见的事。

      现在「手动填写」只在搜不到时出现（见上面那一段），
      这一颗留着是因为它常有用：名字叫不上来、但地图上指得出来。
    -->
    <view v-if="canMap" class="outs">
      <view class="sh-btn sh-btn--soft" @tap="onMap">
        {{ $t("addressPick.onMap") }}
      </view>
    </view>

    <!-- 浏览模式的出口：这一页不负责建地址，但下单要用的那条得有地方去建 -->
    <sh-actionbar v-if="browse" :pad="160">
      <view class="sh-btn" @tap="addAddress">{{ $t("addressPick.addAddress") }}</view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
.cityrow {
  gap: 16rpx;
  margin-bottom: 20rpx;
}
.cityrow__name {
  min-width: 0;
}
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
/* 当前位置那一行：左边说清在哪儿，右边一个动作 */
.hererow {
  gap: 16rpx;
}
.hererow__body {
  min-width: 0;
}
.hererow__use {
  flex-shrink: 0;
}
/* 附近：距离单独一列右对齐 —— 它是这一屏用来比较的那个量 */
.nb__body {
  min-width: 0;
}
.nb__dist {
  flex-shrink: 0;
  margin-inline-start: 16rpx;
}
.noresult {
  padding: 24rpx 0;
}
.row__name {
  display: block;
}
.row__sub {
  display: block;
  margin-top: 8rpx;
}
</style>
