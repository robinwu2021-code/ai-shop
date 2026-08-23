<script setup lang="ts">
/**
 * 经营范围的**唯一**添加入口：一个分级列表走到底（市 › 区 › 街道 › 小区/村）。
 *
 * 方案 v3 定的形状：每一层的行长得一样 —— 有下级的带 › 可下钻、叶子直接勾、
 * 顶部那行是「整个本级」；任何一级都能搜；提报入口在叶子层末尾。
 * 此前「选小区」与「按区/街道」是两个入口、两套交互，而对店主它们是同一件事：「我做哪儿」。
 *
 * 不一次拉整棵树：全国到街道是 4.4 万行，店主真正会点开的只有其中一条路径。
 */
import { computed, nextTick, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import {
  composeAddress, looksLikeEstate, pickOnMap, regionCenter, searchPlaces, searchPlacesNear, streetOf,
} from "@/utils/geo";
import type { PickedLocation } from "@shared/ports/location";
import type { PlaceHit } from "@shared/ports/geo-search";
import type { Community, CommunityApply, GeoTip, Region, ServiceArea } from "@shared/types";

const props = defineProps<{
  visible: boolean;
  areas: ServiceArea[];
}>();
const emit = defineEmits<{
  (e: "update:visible", v: boolean): void;
  (e: "update:areas", v: ServiceArea[]): void;
  (e: "applied", a: CommunityApply): void;
}>();

const { t } = useI18n();

// ---------------------------------------------------------------- 导航
/** 面包屑。空 = 停在省级 */
const trail = ref<Region[]>([]);
const list = ref<Region[]>([]);
const loading = ref(false);
/** 当前停在的这一级（null = 省级列表） */
const current = computed(() => trail.value[trail.value.length - 1] ?? null);
/** 街道/镇是导航终点：这一层平铺聚落（小区/村），不再往下钻区划 */
const atLeaf = computed(() => current.value?.level === "STREET");

/**
 * 本街道下的**官方村/社区**（sys_region 第五级，全国 62 万条里属于这条街道的那几十条）。
 *
 * 这份数据一直都在，只是此前只在「提报」的输入联想里用 —— 于是选择器走到街道就没下文了，
 * 而库里的聚落总共两条，商家看到的是一片空白，只能自己打字提报，同一个村能打出三种写法。
 * 现在直接列出来：点一下＝带着官方村码（查重靠它）与坐标去提报，不用打字。
 */
const villages = ref<Region[]>([]);
const villagesLoading = ref(false);
/**
 * 这个街道下的**小区**（地图来源）。
 *
 * 为什么不能只给官方名录：名录的第五级是**居委会/村委会**，城区一个居委会底下好几个小区，
 * 而商家心里的「我做哪儿」是具体那个小区 —— 只能选到居委会等于把范围放大了一圈，
 * 他的货会出现在隔壁小区的买家面前，而那些人他根本送不到。
 * 农村的村委会≈村，够用；城区必须补这一层，数据只能来自地图。
 */
/**
 * 联想的圆心。点过居委会就以它为心（最准），否则用街道中心。
 * 记在这里而不是读 pickedVillage：商家点完居委会往往会把名字改成小区名，
 * 那一刻 pickedVillage 会被清掉，而**他要找的小区正是在那个居委会附近**。
 */
const scopeCenter = ref<{ lat: number; lng: number } | null>(null);

const estates = ref<PlaceHit[]>([]);
const estatesLoading = ref(false);
/** 名录里的行政单位（社区/居委会/村委会/街道办）不算小区，滤掉 —— 上面那一组已经有它们了 */
const NOT_ESTATE = /(社区|居委会|村委会|街道办|党群|服务中心|警务室|卫生|超市|便利店)/;
async function loadEstates(streetName: string) {
  estatesLoading.value = true;
  estates.value = [];
  try {
    // 顺手把街道中心算出来，给输入框联想当圆心（同一条路径只算一次，有缓存）
    scopeCenter.value = await regionCenter(trail.value.map((r) => r.name));
    // 「街道名 + 住宅小区」比单给街道名准得多：后者返回的全是办事处、社区工作站
    const hits = await searchPlaces(`${streetName} 住宅小区`, cityName.value);
    estates.value = hits.filter((h) => !NOT_ESTATE.test(h.name) && looksLikeEstate(h.name)).slice(0, 10);
  } finally {
    estatesLoading.value = false;
  }
}

async function loadVillages(street: string) {
  villagesLoading.value = true;
  villages.value = [];
  try {
    villages.value = await api.mVillageDict(street);
  } catch {
    villages.value = [];
  } finally {
    villagesLoading.value = false;
  }
}

/** 已开通的聚落按 name 去重用：官方村里已经开通过的，不再重复列一条「去提报」 */
const openedNames = computed(
  () => new Set(communities.value.filter((c) => c.regionCode === current.value?.regionCode).map((c) => c.name)),
);
const villageRows = computed(() =>
  villages.value.filter((v) => !openedNames.value.has(cleanVillageName(v.name))),
);

/** 全部已开通聚落。搜索与叶子层都用它；一次拉、按需过滤（当前量级是个位数到几百） */
const communities = ref<Community[]>([]);
const communitiesLoaded = ref(false);

/** 直开之后聚落列表要重拉：不重拉的话那个村还挂在「官方名录、还没开通」那一段里 */
async function ensureCommunitiesRefresh() {
  communitiesLoaded.value = false;
  await ensureCommunities();
  if (atLeaf.value && current.value) await loadVillages(current.value.regionCode);
}

async function ensureCommunities() {
  if (communitiesLoaded.value) return;
  try {
    communities.value = await api.mCommunities();
  } catch {
    communities.value = [];
  }
  communitiesLoaded.value = true;
}

async function loadLevel(parent?: string) {
  loading.value = true;
  try {
    list.value = await api.mRegions(parent);
  } catch {
    list.value = [];
    uni.showToast({ title: t("store.regionFailed"), icon: "none" });
  } finally {
    loading.value = false;
  }
}

async function open() {
  trail.value = [];
  keyword.value = "";
  resetApply();
  await Promise.all([loadLevel(undefined), ensureCommunities()]);
}

/**
 * 提报表单归零。**每次打开选择器都要做** —— 这些状态挂在组件上，而组件不随面板关闭销毁：
 * 上次提报「A 小区」留下的名字与坐标，会原样出现在下一次给**另一个街道**的提报里，
 * 提交上去就是「挂在 B 街道、坐标在 A 小区」的单子，运营看不出、商家也看不出。
 */
function resetApply() {
  applyOpen.value = false;
  applyName.value = "";
  applyDetail.value = "";
  pickedVillage.value = null;
  pickedPoi.value = null;
  pickedGeo.value = null;
  dictSuggests.value = [];
  poiSuggests.value = [];
  placeSuggests.value = [];
}

watch(() => props.visible, (v) => { if (v) void open(); });

async function drill(r: Region) {
  if (!r.hasChild && r.level !== "STREET") return;
  if (kw.value) {
    // 从搜索结果下钻：面包屑要换成它的真实路径，否则「整个本级」与名字拼接都是错的
    const chain = await api.mRegionPath(r.regionCode).catch(() => [] as Region[]);
    trail.value = chain.length ? chain : [...trail.value, r];
  } else {
    trail.value = [...trail.value, r];
  }
  keyword.value = "";
  // 下钻＝换提报目标，把填了一半的提报清掉（usePlace 直接改 trail，不走这里，所以不会误清）
  resetApply();
  if (r.level !== "STREET") await loadLevel(r.regionCode);
  else {
    await loadVillages(r.regionCode);
    void loadEstates(r.name);
  }
}

async function backTo(i: number) {
  trail.value = trail.value.slice(0, i + 1);
  keyword.value = "";
  // 往回走就是换提报目标：把上一层填了一半的提报清掉，别让它跟着漂到别的街道
  resetApply();
  const cur = trail.value[i];
  if (!cur || cur.level !== "STREET") await loadLevel(cur?.regionCode);
  else {
    await loadVillages(cur.regionCode);
    void loadEstates(cur.name);
  }
}

// ---------------------------------------------------------------- 选中
function has(level: string, refCode: string) {
  return props.areas.some((a) => a.level === level && a.refCode === refCode);
}

/** 名字拼整条路径：光一个「西湖区」全国有好几个，两条同名的商家分不出删哪条 */
function pathName(leafName: string, extra?: Region | null) {
  return [...trail.value.map((x) => x.name), extra?.name, leafName].filter(Boolean).join(" / ");
}

function toggleRegion(r: Region & { path?: string }) {
  if (has(r.level, r.regionCode)) {
    emit("update:areas", props.areas.filter((a) => !(a.level === r.level && a.refCode === r.regionCode)));
    return;
  }
  // 正在这一层里：路径不含自己；搜索命中的用服务端给的路径
  const inTrail = trail.value.some((x) => x.regionCode === r.regionCode);
  const name = r.path
    ? [r.path, r.name].filter(Boolean).join(" / ")
    : inTrail
      ? trail.value.slice(0, trail.value.findIndex((x) => x.regionCode === r.regionCode) + 1).map((x) => x.name).join(" / ")
      : pathName(r.name);
  emit("update:areas", [...props.areas, { level: r.level as ServiceArea["level"], refCode: r.regionCode, name }]);
}

function toggleCommunity(c: Community & { path?: string }) {
  if (has("COMMUNITY", c.communityNo)) {
    emit("update:areas", props.areas.filter((a) => !(a.level === "COMMUNITY" && a.refCode === c.communityNo)));
    return;
  }
  const name = c.path ? [c.path, c.name].join(" / ") : pathName(c.name);
  emit("update:areas", [...props.areas, { level: "COMMUNITY", refCode: c.communityNo, name }]);
}

// ---------------------------------------------------------------- 搜索（任何一级都能搜，P1 走服务端跨级搜索）
const keyword = ref("");
const kw = computed(() => keyword.value.trim());
/** 服务端命中：区划带从省到父级的路径，聚落带所在街道路径 */
const hitRegions = ref<Array<Region & { path: string }>>([]);
const hitCommunities = ref<Array<Community & { path: string }>>([]);
const searching = ref(false);
let searchTimer: ReturnType<typeof setTimeout> | undefined;

watch(kw, (q) => {
  clearTimeout(searchTimer);
  if (q.length < 2) {
    hitRegions.value = [];
    hitCommunities.value = [];
    // 地点列表也要跟着清，并作废在途的那次查询 —— 否则清空搜索框后
    // 上一轮的候选还挂在那儿，看着像「这就是当前结果」
    placeSeq++;
    placeHits.value = [];
    placeSearching.value = false;
    return;
  }
  // 地图地点与区划搜索并行：前者是「深圳市龙华区福城街道」这种整串的唯一出路 ——
  // 区划搜索按单级名字匹配，整串一个字也匹配不上
  void searchPlacesFor(q);
  searchTimer = setTimeout(async () => {
    searching.value = true;
    try {
      const r = await api.mRegionSearch(q);
      hitRegions.value = r.regions.map((x) => ({
        regionCode: x.regionCode, parentCode: "", level: x.level, name: x.name,
        enabled: true, hasChild: x.level !== "STREET", path: x.path,
      } as Region & { path: string }));
      hitCommunities.value = r.communities.map((x) => ({
        communityNo: x.communityNo, name: x.name, regionCode: x.regionCode ?? undefined, path: x.path,
      } as unknown as Community & { path: string }));
    } catch {
      // 搜索接口不在（老后端）：退回本地过滤，至少当前层还能搜
      hitRegions.value = list.value.filter((r) => r.name.includes(q)).map((r) => ({ ...r, path: "" }));
      hitCommunities.value = communities.value.filter((c) => c.name.includes(q)).slice(0, 30)
        .map((c) => ({ ...c, path: "" }));
    } finally {
      searching.value = false;
    }
  }, 250);
});

/**
 * 地图地点（原生高德联想）。区划树只认「本级名字」，而店主嘴里的是「深圳市龙华区福城街道」
 * 或者一个小区名 —— 那既不是区划，也多半不在聚落库里（库里现在总共两条）。
 * 这条列表把整串换成**带坐标**的候选，点一条就能定位过去。
 */
const placeHits = ref<PlaceHit[]>([]);
const placeSearching = ref(false);
let placeSeq = 0;
async function searchPlacesFor(q: string, city?: string) {
  const mine = ++placeSeq;
  placeSearching.value = true;
  try {
    const hits = await searchPlaces(q, city);
    if (mine === placeSeq) placeHits.value = hits.slice(0, 8);
  } finally {
    if (mine === placeSeq) placeSearching.value = false;
  }
}

/**
 * 选中一条地图地点：**先把面包屑挪到它所在的街道**，再打开提报表单并把名字/地址/坐标填好。
 *
 * 挪面包屑这一步是关键：提报单挂在街道下，挂错了运营要么改要么驳回。
 * 地址里能抠出街道名（「…龙华区福城街道…」）就用它去搜区划；抠不到或搜不到唯一命中，
 * 就停在原地并说一句 —— 让店主自己走到对的街道，好过悄悄挂到一个错的上面。
 */
async function usePlace(p: PlaceHit) {
  const street = streetOf(p.address);
  let landed = false;
  if (street) {
    try {
      const r = await api.mRegionSearch(street);
      const streets = r.regions.filter((x) => x.level === "STREET");
      const hit = streets.length === 1
        ? streets[0]
        : streets.find((x) => (x.path ? p.address.includes(x.path.split(" / ").slice(-1)[0] ?? "") : false));
      if (hit) {
        const chain = await api.mRegionPath(hit.regionCode).catch(() => [] as Region[]);
        if (chain.length) {
          trail.value = chain;
          landed = true;
          await loadVillages(hit.regionCode);
          void loadEstates(chain[chain.length - 1]?.name ?? "");
        }
      }
    } catch {
      // 搜不到就不挪 —— 停在原地比挂错街道好
    }
  }
  keyword.value = "";
  placeHits.value = [];
  if (!landed && !atLeaf.value) {
    uni.showToast({ title: t("store.placeNeedStreet"), icon: "none" });
    return;
  }
  applyOpen.value = true;
  applyName.value = p.name.slice(0, 30);
  pickedGeo.value = { lat: p.lat, lng: p.lng, name: p.name, address: p.address };
  pickedPoi.value = null;
  pickedVillage.value = null;
}

/** 区划行：搜索时是服务端命中，否则是本级 */
const levelRows = computed<Array<Region & { path?: string }>>(() =>
  kw.value ? hitRegions.value : list.value,
);
/** 聚落行：搜索时是服务端命中；叶子层是本街道下的 */
const settleRows = computed<Array<Community & { path?: string }>>(() => {
  if (kw.value) return hitCommunities.value;
  if (!atLeaf.value) return [];
  return communities.value.filter((c) => c.regionCode === current.value?.regionCode);
});
const nothing = computed(() =>
  !loading.value && !searching.value && kw.value.length !== 1 && !levelRows.value.length && !settleRows.value.length,
);

// ---------------------------------------------------------------- 叶子层提报（带官方村名词典）
const applyOpen = ref(false);
const applyName = ref("");
/**
 * 门牌号/楼栋。地图给的地址常常只到路名（「观光路」），而运营要判「是不是同一个小区的另一个叫法」、
 * 买家要照着找门 —— 差的就是这一截。单独一个框而不是让他改上面那条地址：
 * 地图给的部分是可信的，手改容易改坏。
 */
const applyDetail = ref("");
/**
 * 联想列表出来时把提报块滚进视野。它长在滚动区末尾，键盘一弹就被压在下面 ——
 * 商家看到的是「输了字没反应」，其实候选就在屏幕外。
 */
const scrollInto = ref("");
function revealApply() {
  scrollInto.value = "";
  void nextTick(() => {
    scrollInto.value = "apply-block";
  });
}
const pickedVillage = ref<Region | null>(null);
const dictSuggests = ref<Region[]>([]);
let dictTimer: ReturnType<typeof setTimeout> | undefined;

/** 「富城村村民委员会」→「富城村」：聚落叫的是地名，不是机构名 */
function cleanVillageName(official: string): string {
  return official.replace(/(村民委员会|居民委员会|村委会|居委会|委员会)$/, "") || official;
}

/**
 * 高德 POI 联想（经后端代理）。选中一条，提报就带上**那个小区的**坐标与地址 ——
 * 之前坐标是「提交那一刻的定位」，尽力而为还可能为空：裁决通过后聚落没坐标，买家永远落不进围栏。
 * 后端没配 Web 服务 key 时返回空数组，这块就不出现，提报照旧。
 */
const poiSuggests = ref<GeoTip[]>([]);
const pickedPoi = ref<GeoTip | null>(null);
/** 提报表单里的地图联想（原生高德，不吃后端 Web key） */
const placeSuggests = ref<PlaceHit[]>([]);
/** 市名给高德缩范围：面包屑第二级就是市。搜「福成」这种两字词，不缩范围会全国乱给 */
const cityName = computed(() => trail.value.find((r) => r.level === "CITY")?.name);

watch(applyName, (v: string) => {
  if (pickedVillage.value && v !== cleanVillageName(pickedVillage.value.name)) pickedVillage.value = null;
  if (pickedPoi.value && v !== pickedPoi.value.name) pickedPoi.value = null;
  if (pickedGeo.value && v !== pickedGeo.value.name) pickedGeo.value = null;
  clearTimeout(dictTimer);
  const q = v.trim();
  if (q.length < 2 || pickedVillage.value || pickedPoi.value || pickedGeo.value || !atLeaf.value) {
    dictSuggests.value = [];
    poiSuggests.value = [];
    placeSuggests.value = [];
    return;
  }
  dictTimer = setTimeout(async () => {
    // 两路并行：官方村名词典（库里 62 万条，管「叫什么、归哪个街道」）
    // 与地图地点（管「在哪儿」—— 带坐标）。两件事缺一不可，所以两个列表都给。
    const center = scopeCenter.value;
    const [dict, places] = await Promise.allSettled([
      api.mVillageDict(current.value!.regionCode, q),
      /*
       * 以所在街道/居委会为圆心搜，而不是拼前缀按城市搜：
       * 「福安」按城市搜会返回福建的福安市，拼成「福城街道 福安」则被街道办顶满，
       * 而以坐标圈 5 公里搜「福安」返回的正是福安雅园 A/B/C 区（都实测过）。
       */
      center ? searchPlacesNear(q, center, 5000, cityName.value)
             : searchPlaces(q, cityName.value),
    ]);
    dictSuggests.value = dict.status === "fulfilled" ? dict.value.slice(0, 5) : [];
    placeSuggests.value = places.status === "fulfilled"
      ? places.value.filter((p) => looksLikeEstate(p.name)).slice(0, 5)
      : [];
    if (dictSuggests.value.length || placeSuggests.value.length) revealApply();
  }, 300);
});

function pickVillage(r: Region) {
  pickedVillage.value = r;
  pickedPoi.value = null;
  applyName.value = cleanVillageName(r.name);
  dictSuggests.value = [];
  poiSuggests.value = [];
  placeSuggests.value = [];
}

/**
 * 点一条官方村：直接把提报表单填好 —— 名字用去掉「村民委员会」后缀的地名，村码留着给运营查重，
 * 坐标顺手用原生高德按「市+村名」搜一次（`sys_region` 没有坐标列，只能这么补）。
 * 搜不到也不挡：表单里还有「在地图上找」那条路。
 */
async function useVillage(v: Region) {
  applyOpen.value = true;
  // 表单长在长长的村名单下面，不滚过去的话点完像是没反应
  revealApply();
  pickedVillage.value = v;
  pickedPoi.value = null;
  pickedGeo.value = null;
  const name = cleanVillageName(v.name);
  applyName.value = name;
  dictSuggests.value = [];
  poiSuggests.value = [];
  placeSuggests.value = [];
  applyDetail.value = "";
  // 库里已经有坐标（高德批量补录过的）就直接用 —— 省一次搜索，也省得两次结果不一致
  if (v.latE6 != null && v.lngE6 != null) {
    pickedGeo.value = { lat: v.latE6 / 1e6, lng: v.lngE6 / 1e6, name, address: "" };
    // 圆心挪到这个居委会：接下来他把名字改成小区名时，联想就在这一片找
    scopeCenter.value = { lat: v.latE6 / 1e6, lng: v.lngE6 / 1e6 };
    return;
  }
  const hits = await searchPlaces(cityName.value ? `${cityName.value}${name}` : name, cityName.value);
  const top = hits[0];
  if (top && !pickedGeo.value) {
    pickedGeo.value = { lat: top.lat, lng: top.lng, name, address: top.address };
  }
}

/**
 * 点一个小区：直接进提报表单，名字/门牌地址/坐标都填好。
 *
 * 与官方村不同，这一类**仍然要运营审**：名字是地图上的叫法，
 * 同一个小区可能有「XX 花园」「XX 花园 A 区」几种写法，免审会长出几个重复聚落，
 * 而商家勾选时分不清该勾哪个。带着地址与坐标去审，运营几秒就能判完。
 */
function useEstate(p: PlaceHit) {
  applyOpen.value = true;
  revealApply();
  pickedGeo.value = { lat: p.lat, lng: p.lng, name: p.name, address: p.address };
  pickedPoi.value = null;
  pickedVillage.value = null;
  applyName.value = p.name.slice(0, 30);
  applyDetail.value = "";
  dictSuggests.value = [];
  poiSuggests.value = [];
  placeSuggests.value = [];
}

/** 选中一条地图联想：名字照抄，坐标与门牌地址一起带上 */
function pickPlace(p: PlaceHit) {
  pickedGeo.value = { lat: p.lat, lng: p.lng, name: p.name, address: p.address };
  revealApply();
  pickedPoi.value = null;
  pickedVillage.value = null;
  applyName.value = p.name.slice(0, 30);
  dictSuggests.value = [];
  poiSuggests.value = [];
  placeSuggests.value = [];
}

function pickPoi(p: GeoTip) {
  pickedPoi.value = p;
  pickedGeo.value = null;
  pickedVillage.value = null;
  applyName.value = p.name;
  dictSuggests.value = [];
  poiSuggests.value = [];
}

/**
 * 地图选点：原生高德选点页自带**联想搜索 + 回到当前位置**，选中即拿到 POI 名、门牌地址与坐标。
 *
 * 为什么这条是主路而不是上面那个联想列表：联想走后端 `/biz/geo/tips`（高德 Web 服务），
 * 没配 `AMAP_WEB_KEY` 时永远是空的；而选点页用的是包里的原生 SDK（Android Key 已生效），
 * 今天就能用。两条都留着：有 Web Key 时列表先出，没有也不耽误。
 *
 * 名字只在为空时代填 —— 商家常把小区叫成「XX 花园」而地图上是「XX 花园(北区)」，
 * 覆盖掉他刚敲的名字等于替他改了提报内容。
 */
const pickedGeo = ref<PickedLocation | null>(null);
const picking = ref(false);
async function pickOnMapForApply() {
  if (picking.value) return;
  picking.value = true;
  try {
    /*
     * 初始中心按「离要标的点最近」排：已标过的 › 联想选中的 › **当前所在区域** › 当前设备位置。
     * 区域这一档是店主真正要的：他在店里给另一个区配范围，开局落在自己脚下，
     * 等于每次都要先把地图拖几百公里。
     */
    const init = pickedGeo.value
      ? { lat: pickedGeo.value.lat, lng: pickedGeo.value.lng }
      : pickedPoi.value?.latE6 != null && pickedPoi.value?.lngE6 != null
        ? { lat: pickedPoi.value.latE6 / 1e6, lng: pickedPoi.value.lngE6 / 1e6 }
        : await regionCenter(trail.value.map((r) => r.name));
    const p = await pickOnMap(t, init);
    if (!p) return;
    pickedGeo.value = p;
    pickedPoi.value = null;
    dictSuggests.value = [];
    poiSuggests.value = [];
    if (!applyName.value.trim() && p.name) applyName.value = p.name.slice(0, 30);
  } finally {
    picking.value = false;
  }
}

/** 提报要带的地址与坐标：地图选点 > 联想选中 > 都没有则留空（运营多半会驳回，端上已提示） */
const applyGeo = computed(() => {
  const g = pickedGeo.value;
  if (g) return { address: composeAddress(g), latE6: Math.round(g.lat * 1e6), lngE6: Math.round(g.lng * 1e6) };
  const p = pickedPoi.value;
  if (p?.latE6 != null && p.lngE6 != null) return { address: p.address ?? "", latE6: p.latE6, lngE6: p.lngE6 };
  return null;
});

async function submitApply() {
  const street = current.value;
  const name = applyName.value.trim();
  if (!street || !name) {
    uni.showToast({ title: t("store.applyNeedName"), icon: "none" });
    return;
  }
  /*
   * 没坐标也让提 —— 但要先说清后果：通过后的聚落没坐标，买家用定位永远找不到，
   * 而这件事商家自己一辈子查不出来。拦死不合适（地图搜不到的新小区确实存在）。
   */
  const geo = applyGeo.value;
  const detail = applyDetail.value.trim();
  if (!geo) {
    const go = await new Promise<boolean>((resolve) => {
      uni.showModal({
        title: t("store.applyNoGeoTitle"),
        content: t("store.applyNoGeoBody"),
        confirmText: t("store.applyNoGeoGo"),
        cancelText: t("store.applyNoGeoPick"),
        success: (r) => resolve(!!r.confirm),
        fail: () => resolve(false),
      });
    });
    if (!go) {
      void pickOnMapForApply();
      return;
    }
  }
  try {
    const a = await api.mApplyCommunity({
      name,
      // 门牌号拼在地图地址后面：运营查重与买家找门都靠这一截
      address: [geo?.address, detail].filter(Boolean).join(" ") || undefined,
      regionCode: street.regionCode,
      kind: pickedVillage.value ? "VILLAGE" : "ESTATE",
      originCode: pickedVillage.value?.regionCode,
      latE6: geo?.latE6,
      lngE6: geo?.lngE6,
    });
    emit("applied", a);
    /*
     * 官方名录里的村是**免审直开**的（后端 submitApply 里判的）：这时候提报单回来就是
     * APPROVED 且带着新建的聚落号。既然他刚才要的就是「做这个村」，直接替他勾上 ——
     * 让他自己再从列表里找一遍那条刚建好的，属于把系统知道的事推回给人做。
     */
    if (a.status === "APPROVED" && a.communityNo) {
      const name = pathName(a.name);
      if (!has("COMMUNITY", a.communityNo)) {
        emit("update:areas", [...props.areas,
          { level: "COMMUNITY" as ServiceArea["level"], refCode: a.communityNo, name }]);
      }
      await ensureCommunitiesRefresh();
      uni.showToast({ title: t("store.applyOpened"), icon: "none" });
    } else {
      uni.showToast({ title: t("store.applySubmitted"), icon: "none" });
    }
    resetApply();
  } catch (e) {
    uni.showToast({ title: (e as Error)?.message || t("store.applyFailed"), icon: "none" });
  }
}

function close() {
  emit("update:visible", false);
}
</script>

<template>
  <view v-if="visible" class="mask" @tap="close">
    <view class="sheet" @tap.stop>
      <view class="sheet__head">
        <text class="sheet__title">{{ $t("store.picker.title") }}</text>
        <text class="sheet__count">{{ $t("store.picker.selected", { n: areas.length }) }}</text>
      </view>

      <view class="search">
        <sh-icon name="search" :size="18" color="var(--sh-sub)"></sh-icon>
        <input v-model="keyword" class="search__input" :maxlength="20" :placeholder="$t('store.picker.searchPh')" />
      </view>

      <!-- 面包屑：当前在哪一级。搜索时不显示 —— 结果是跨级的 -->
      <view v-if="!kw" class="crumb">
        <text class="crumb__i" @tap="backTo(-1)">{{ $t("store.regionRoot") }}</text>
        <text v-for="(x, i) in trail" :key="x.regionCode" class="crumb__i" :class="{ 'is-cur': i === trail.length - 1 }" @tap="backTo(i)">
          › {{ x.name }}
        </text>
      </view>

      <!-- 整个本级：中间层级也要能整个选，否则框一个区要点开十几个街道 -->
      <view v-if="!kw && current" class="whole" :class="{ 'is-on': has(current.level, current.regionCode) }" @tap="toggleRegion(current)">
        <text class="whole__t">{{ $t("store.picker.wholeLevel", { s: current.name }) }}</text>
        <text v-if="has(current.level, current.regionCode)" class="whole__on">{{ $t("store.picker.picked") }}</text>
        <text v-else-if="current.level === 'DISTRICT' || current.level === 'CITY'" class="whole__audit">{{ $t("store.picker.needAudit") }}</text>
      </view>

      <scroll-view scroll-y class="body" :scroll-into-view="scrollInto" scroll-with-animation>
        <text v-if="loading" class="hint">{{ $t("common.loading") }}</text>
        <template v-else>
          <!-- 区划行：同一种形状，有下级的带 › -->
          <view v-for="r in (atLeaf && !kw ? [] : levelRows)" :key="r.regionCode" class="row">
            <view class="row__main" @tap="drill(r)">
              <text class="row__name">{{ r.name }}</text>
              <text v-if="r.path" class="row__sub">{{ r.path }}</text>
            </view>
            <view class="row__check" :class="{ 'is-on': has(r.level, r.regionCode) }" @tap="toggleRegion(r)">
              <text v-if="has(r.level, r.regionCode)" class="row__tick">✓</text>
            </view>
            <sh-icon v-if="r.hasChild || r.level === 'STREET'" name="chevronRight" :size="18" color="var(--sh-sub)" @tap="drill(r)"></sh-icon>
          </view>

          <!-- 聚落行（小区/村同列）：叶子直接勾 -->
          <view v-for="c in settleRows" :key="c.communityNo" class="row" @tap="toggleCommunity(c)">
            <view class="row__main">
              <text class="row__name">{{ c.name }}</text>
              <text v-if="c.path || c.address" class="row__sub">{{ c.path || c.address }}</text>
            </view>
            <view class="row__check" :class="{ 'is-on': has('COMMUNITY', c.communityNo) }">
              <text v-if="has('COMMUNITY', c.communityNo)" class="row__tick">✓</text>
            </view>
          </view>

          <!-- 地图地点：整串地名与小区名的唯一出路，每条都带坐标 -->
          <template v-if="kw && placeHits.length">
            <text class="group">{{ $t("store.picker.placeGroup") }}</text>
            <view v-for="p in placeHits" :key="p.name + p.address" class="place" @tap="usePlace(p)">
              <view class="place__main">
                <text class="place__name">{{ p.name }}</text>
                <text v-if="p.address" class="place__addr">{{ p.address }}</text>
              </view>
              <sh-icon name="pin" :size="16" color="var(--sh-sub)"></sh-icon>
            </view>
          </template>

          <text v-if="nothing && !placeHits.length && !placeSearching" class="hint">{{ $t("store.picker.searchEmpty") }}</text>

          <!-- 街道下的小区（地图）：名录里没有这一层，而它才是商家心里的「我做哪儿」 -->
          <template v-if="atLeaf && !kw && estates.length">
            <text class="group">{{ $t("store.picker.estateGroup") }}</text>
            <view v-for="p in estates" :key="p.name + p.address" class="place" @tap="useEstate(p)">
              <view class="place__main">
                <text class="place__name">{{ p.name }}</text>
                <text v-if="p.address" class="place__addr">{{ p.address }}</text>
              </view>
              <text class="place__go">{{ $t("store.picker.villageGo") }}</text>
            </view>
          </template>

          <!-- 街道下的官方村/社区：点一条即带村码与坐标去提报，不用打字 -->
          <template v-if="atLeaf && !kw && villageRows.length">
            <text class="group">{{ $t("store.picker.villageGroup") }}</text>
            <view v-for="v in villageRows" :key="v.regionCode" class="place" @tap="useVillage(v)">
              <view class="place__main">
                <text class="place__name">{{ v.name }}</text>
                <text class="place__addr">
                  {{ v.latE6 != null ? $t("store.picker.villageLocated") : $t("store.picker.villageHint") }}
                </text>
              </view>
              <text class="place__go">{{ $t("store.picker.villageGo") }}</text>
            </view>
          </template>

          <!-- 叶子层末尾：提报 -->
          <template v-if="atLeaf && !kw">
            <view v-if="!applyOpen" class="row row--apply" @tap="applyOpen = true; revealApply()">
              <text class="row__apply">{{ $t("store.picker.applyEntry") }}</text>
            </view>
            <view v-else id="apply-block" class="apply">
              <text class="hint">{{ $t("store.applyToStreet", { s: current?.name }) }}</text>
              <input
                v-model="applyName"
                class="field__input"
                :maxlength="30"
                :cursor-spacing="160"
                :placeholder="$t('store.dictHint')"
                @focus="revealApply"
              />
              <view v-if="dictSuggests.length" class="apply__sug">
                <text v-for="d in dictSuggests" :key="d.regionCode" class="sh-chip" @tap="pickVillage(d)">{{ d.name }}</text>
              </view>
              <view v-if="placeSuggests.length" class="apply__poi">
                <view v-for="p in placeSuggests" :key="p.name + p.address" class="apply__poi-row" @tap="pickPlace(p)">
                  <text class="apply__poi-name">{{ p.name }}</text>
                  <text v-if="p.address" class="apply__poi-addr">{{ p.address }}</text>
                </view>
              </view>
              <text v-if="pickedVillage" class="hint">{{ $t("store.dictPicked", { s: pickedVillage.name }) }}</text>
              <text v-if="pickedVillage" class="hint">{{ $t("store.villageToEstate") }}</text>
              <view v-if="poiSuggests.length" class="apply__poi">
                <view v-for="p in poiSuggests" :key="p.name + p.address" class="apply__poi-row" @tap="pickPoi(p)">
                  <text class="apply__poi-name">{{ p.name }}</text>
                  <text v-if="p.address" class="apply__poi-addr">{{ p.address }}</text>
                </view>
              </view>
              <text v-if="pickedPoi" class="hint">{{ $t("store.poiPicked", { s: pickedPoi.address || pickedPoi.name }) }}</text>
              <view class="apply__map" :class="{ 'is-ok': !!pickedGeo }" @tap="pickOnMapForApply">
                <sh-icon name="pin" :size="18" :color="pickedGeo ? 'var(--sh-primary-text)' : 'var(--sh-sub)'"></sh-icon>
                <text class="apply__map-t">
                  {{ picking ? $t("common.loading") : pickedGeo ? $t("store.applyRepick") : $t("store.applyPick") }}
                </text>
              </view>
              <text v-if="pickedGeo" class="hint">{{ $t("store.poiPicked", { s: applyGeo?.address || pickedGeo.name }) }}</text>
              <input
                v-model="applyDetail"
                class="field__input"
                :maxlength="40"
                :cursor-spacing="160"
                :placeholder="$t('store.applyDetailPh')"
                @focus="revealApply"
              />
              <view class="apply__btns">
                <text class="sh-btn sh-btn--soft apply__go" @tap="submitApply">{{ $t("common.submit") }}</text>
                <text class="mini" @tap="applyOpen = false">{{ $t("common.cancel") }}</text>
              </view>
            </view>
          </template>
        </template>
      </scroll-view>

      <view class="foot">
        <view class="sh-btn" @tap="close">{{ $t("store.picker.done", { n: areas.length }) }}</view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: var(--sh-scrim);
}
.sheet {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  height: 84vh;
  border-radius: 32rpx 32rpx 0 0;
  background: var(--sh-surface);
}
.sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 32rpx 16rpx;
}
.sheet__title {
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.sheet__count {
  font-size: 26rpx;
  color: var(--sh-sub);
}
.search {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin: 0 24rpx;
  height: 80rpx;
  padding: 0 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
}
.search__input {
  flex: 1;
  font-size: 28rpx;
  color: var(--sh-ink);
}
.crumb {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
  margin: 20rpx 32rpx 0;
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.crumb__i.is-cur {
  color: var(--sh-ink);
  font-weight: 600;
}
.whole {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16rpx 24rpx 0;
  padding: 20rpx 24rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
.whole.is-on {
  background: var(--sh-primary-tint);
}
.whole__t {
  font-size: 26rpx;
  color: var(--sh-ink);
}
.whole__on {
  padding: 4rpx 16rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 24rpx;
}
.whole__audit {
  font-size: 24rpx;
  color: var(--sh-warning);
}
.body {
  flex: 1;
  min-height: 0;
  margin-top: 12rpx;
}
.row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 24rpx 32rpx;
  border-bottom: 2rpx solid var(--sh-line);
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__name {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.row__sub {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.row__check {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44rpx;
  height: 44rpx;
  border-radius: 9999px;
  border: 3rpx solid var(--sh-line);
  box-sizing: border-box;
}
.row__check.is-on {
  border-color: var(--sh-primary);
  background: var(--sh-primary);
}
.row__tick {
  font-size: 24rpx;
  color: var(--sh-on-primary);
}
.row--apply {
  border-bottom: none;
}
.row__apply {
  font-size: 26rpx;
  color: var(--sh-primary-text);
}
.apply {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  padding: 12rpx 32rpx 24rpx;
}
.apply__sug {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}
.group {
  padding: 16rpx 8rpx 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.place {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 20rpx 8rpx;
  border-bottom: 1rpx solid var(--sh-line);
}
.place__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.place__name {
  font-size: 30rpx;
  color: var(--sh-text);
}
.place__addr {
  font-size: 22rpx;
  color: var(--sh-sub);
}
.place__go {
  flex-shrink: 0;
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.apply__map {
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  align-self: flex-start;
  padding: 14rpx 24rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
.apply__map.is-ok {
  background: var(--sh-primary-tint);
}
.apply__map-t {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.apply__map.is-ok .apply__map-t {
  color: var(--sh-primary-text);
}
.apply__poi {
  display: flex;
  flex-direction: column;
  border-radius: 16rpx;
  background: var(--sh-faint);
  overflow: hidden;
}
.apply__poi-row {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  padding: 16rpx 20rpx;
  border-bottom: 1rpx solid var(--sh-line);
}
.apply__poi-row:last-child {
  border-bottom: none;
}
.apply__poi-name {
  font-size: 28rpx;
  color: var(--sh-text);
}
.apply__poi-addr {
  font-size: 22rpx;
  color: var(--sh-sub);
}
.apply__btns {
  display: flex;
  gap: 16rpx;
}
.apply__go {
  flex: 1;
}
.hint {
  display: block;
  padding: 16rpx 32rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--sh-sub);
}
.mini {
  padding: 16rpx 28rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  color: var(--sh-sub);
  font-size: 24rpx;
}
.foot {
  padding: 16rpx 24rpx;
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  border-top: 2rpx solid var(--sh-line);
}
</style>
