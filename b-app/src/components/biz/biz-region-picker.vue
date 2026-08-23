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
import { getLocation } from "@shared/ports/location";
import type { PlaceHit } from "@shared/ports/geo-search";
import type { Community, CommunityApply, GeoTip, Region, RegionSearchResult, ServiceArea } from "@shared/types";

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
    /*
     * **系统里已经有的小区不出现在这一组**：它已经在上面的聚落清单里可以直接勾，
     * 在这儿再出一条「提报 ›」，商家多半会点那条 —— 然后等来一条注定被驳回的单。
     */
    estates.value = hits
      .filter((h) => !NOT_ESTATE.test(h.name) && looksLikeEstate(h.name) && !isOpened(h.name))
      .slice(0, 10);
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
/** 「阳光花园」「阳光花园小区」「阳光花园(北区)」在商家嘴里是同一个地方 —— 与后端同一套归一 */
function normalizeName(s: string) {
  return s.replace(/[（(].*?[）)]/g, "")
    .replace(/(小区|花园|家园|新村|苑|园|村|社区|居委会|村民委员会|居民委员会)+$/g, "")
    .trim();
}

/** 这个名字在系统里是不是已经有了。**有就不该再出现「提报」入口** —— 直接勾就是了 */
function isOpened(name: string) {
  const x = normalizeName(name);
  if (!x) return false;
  for (const n of openedNames.value) {
    const y = normalizeName(n);
    if (y && (x === y || x.startsWith(y) || y.startsWith(x))) return true;
  }
  return false;
}

const villageRows = computed(() => villages.value.filter((v) => !isOpened(v.name)));

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
  browsing.value = false;
  levelFilter.value = "";
  chosenOpen.value = false;
  nearby.value = [];
  resetApply();
  // 「门店附近」是空输入时的默认内容；它顺带把 storeCenter 填上，
  // 而搜索要用同一个坐标给村级排距离 —— 先起它，别等用户开口才去要位置
  void loadNearby();
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
  levelFilter.value = "";
  // 点进一级就是在浏览了：空输入时那份省级列表也走这条路，
  // 不切过去的话「整个浙江省」那一行不出现，而它正是这一级最该有的选项
  browsing.value = true;
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
  levelFilter.value = "";
  trail.value = trail.value.slice(0, i + 1);
  keyword.value = "";
  // 面包屑常驻，搜索态点它就是「回到这一级继续浏览」
  browsing.value = true;
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

/** 覆盖项上限。到顶时要说清是哪一条加不进去，而不是静默丢掉 */
const MAX_AREAS = 200;

/**
 * 区划码是**层级前缀码**：省 2 位、市 4、区 6、街道 9、村 12。
 * 「这条被那条覆盖了吗」因此就是一次前缀判断 —— 与后端展开覆盖范围用的是同一条规则
 * （`openCommunityNosUnderRegion` 走 `LIKE '前缀%'`）。两边口径必须一致，
 * 否则端上显示「已覆盖」而后端并没有展开进去，或者反过来。
 */
function isRegionLevel(level: string) {
  return level === "PROVINCE" || level === "CITY" || level === "DISTRICT" || level === "STREET";
}

/**
 * 已选里**盖住这个区划码**的那一条（不含自己）。返回它是为了把话说全：
 * 「已被『浙江省』覆盖」比一个灰掉的勾有用得多 —— 后者只让人以为坏了。
 */
function coveredBy(regionCode: string): ServiceArea | null {
  if (!regionCode) return null;
  return props.areas.find(
    (a) => isRegionLevel(a.level) && a.refCode !== regionCode && regionCode.startsWith(a.refCode),
  ) ?? null;
}

/**
 * 聚落被哪条区划盖住。聚落自己没有区划码，靠它挂的街道码判断 ——
 * 拿不到街道码时**宁可认为没被覆盖**：错报「已覆盖」会让人以为已经选上了，
 * 而漏报最多是多勾一条，后端保存时还会再归一一次。
 */
function communityCoveredBy(regionCode?: string): ServiceArea | null {
  return regionCode ? coveredBy(regionCode) : null;
}

/** 加一条覆盖项，顺手把**被它盖住的子项**收掉（R3/R5：父子只留父） */
function addArea(next: ServiceArea) {
  const kept = props.areas.filter((a) => {
    if (!isRegionLevel(next.level)) return true;
    // 子区划：码以父码开头即被覆盖
    if (isRegionLevel(a.level)) return !(a.refCode !== next.refCode && a.refCode.startsWith(next.refCode));
    // 子聚落：靠 areaRegion 记下的街道码判断，记不到就留着（见 communityCoveredBy）
    const rc = areaRegion.value[a.refCode];
    return !(rc && rc.startsWith(next.refCode));
  });
  const dropped = props.areas.length - kept.length;
  if (kept.length >= MAX_AREAS) {
    uni.showToast({ title: t("store.picker.tooMany", { n: MAX_AREAS }), icon: "none" });
    return;
  }
  emit("update:areas", [...kept, next]);
  if (dropped > 0) {
    // 合并要说出来：不说的话商家会以为自己刚勾的那几个小区被系统弄丢了
    uni.showToast({ title: t("store.picker.merged", { n: dropped, s: next.name.split(" / ").pop() ?? next.name }), icon: "none" });
  }
}

/**
 * 聚落 → 它挂的街道码。**只活在这一次会话里**：用于「勾了整个街道，把它底下已勾的小区收掉」。
 * 不进 ServiceArea 是因为那是要回传给后端的形状，多一个字段就要改契约、改表、改 ops ——
 * 而这件事只在选择器里发生。
 */
const areaRegion = ref<Record<string, string>>({});

async function toggleRegion(r: Region & { path?: string }) {
  if (has(r.level, r.regionCode)) {
    emit("update:areas", props.areas.filter((a) => !(a.level === r.level && a.refCode === r.regionCode)));
    return;
  }
  const cover = coveredBy(r.regionCode);
  if (cover) {
    uni.showToast({ title: t("store.picker.coveredBy", { s: cover.name.split(" / ").pop() ?? cover.name }), icon: "none" });
    return;
  }
  // 正在这一层里：路径不含自己；搜索命中的用服务端给的路径
  const inTrail = trail.value.some((x) => x.regionCode === r.regionCode);
  const name = r.path
    ? [r.path, r.name].filter(Boolean).join(" / ")
    : inTrail
      ? trail.value.slice(0, trail.value.findIndex((x) => x.regionCode === r.regionCode) + 1).map((x) => x.name).join(" / ")
      : pathName(r.name);
  /*
   * **省/市/区要先问一句**（R13）：这三级要运营审核、影响面差着量级，
   * 而它与「勾一个小区」在界面上只差一个位置 —— 手一滑就勾上了，
   * 而后果（一条待审记录、买家侧什么也没变）要过几天才看得出来。
   */
  if (r.level !== "STREET") {
    const ok = await confirmWide(r.level, name);
    if (!ok) return;
  }
  addArea({ level: r.level as ServiceArea["level"], refCode: r.regionCode, name });
}

/** 整片加入前的确认。街道不问 —— 它自助生效，量级与小区同档 */
function confirmWide(level: string, name: string): Promise<boolean> {
  const scope = level === "PROVINCE" ? t("store.picker.levelProvince")
    : level === "CITY" ? t("store.picker.levelCity") : t("store.picker.levelDistrict");
  return new Promise((resolve) => {
    uni.showModal({
      title: t("store.picker.wideTitle", { s: scope }),
      content: t("store.picker.wideBody", { s: name.split(" / ").pop() ?? name }),
      confirmText: t("store.picker.wideOk"),
      success: (res) => resolve(Boolean(res.confirm)),
      fail: () => resolve(false),
    });
  });
}

function toggleCommunity(c: Community & { path?: string }) {
  if (has("COMMUNITY", c.communityNo)) {
    emit("update:areas", props.areas.filter((a) => !(a.level === "COMMUNITY" && a.refCode === c.communityNo)));
    return;
  }
  const cover = communityCoveredBy(c.regionCode);
  if (cover) {
    uni.showToast({ title: t("store.picker.coveredBy", { s: cover.name.split(" / ").pop() ?? cover.name }), icon: "none" });
    return;
  }
  if (c.regionCode) areaRegion.value[c.communityNo] = c.regionCode;
  const name = c.path ? [c.path, c.name].join(" / ") : pathName(c.name);
  addArea({ level: "COMMUNITY", refCode: c.communityNo, name });
}

// ---------------------------------------------------------------- 搜索（任何一级都能搜，P1 走服务端跨级搜索）
const keyword = ref("");
const kw = computed(() => keyword.value.trim());
/** 服务端命中：区划带从省到父级的路径，聚落带所在街道路径 */
const hitRegions = ref<Array<Region & { path: string }>>([]);
const hitCommunities = ref<Array<Community & { path: string }>>([]);
/**
 * 搜到的**还没开通的官方村**。此前搜索只认市/区/街道与已开通聚落 ——
 * 商家打「狮径」什么也搜不到，只能自己一级级点到街道才发现名录里一直有这一条。
 * 点一条即挂到它的街道并直接开通（官方村免审），不用先把面包屑走一遍。
 */
const hitVillages = ref<NonNullable<RegionSearchResult["villages"]>>([]);
const searching = ref(false);
let searchTimer: ReturnType<typeof setTimeout> | undefined;

/**
 * 门槛分两档：**区划一个字就搜，聚落两个字**。
 *
 * 「京」「沪」「渝」本身就是一个完整的省级简称，卡两个字等于告诉他「搜不到」；
 * 而村级 62 万行，一个字（「新」「东」）能命中上万条，那不是给人挑的列表。
 */
watch(kw, (q) => {
  clearTimeout(searchTimer);
  /*
   * 一开口就切出浏览态。**面包屑留着**（它说明「回去之后落在哪一级」），
   * 但列表必须换成搜索结果 —— 否则打了字什么也没变，看着像搜索坏了。
   */
  if (q) browsing.value = false;
  if (!q) {
    hitRegions.value = [];
    hitCommunities.value = [];
    hitVillages.value = [];
    // 地点列表也要跟着清，并作废在途的那次查询 —— 否则清空搜索框后
    // 上一轮的候选还挂在那儿，看着像「这就是当前结果」
    placeSeq++;
    placeHits.value = [];
    placeSearching.value = false;
    return;
  }
  // 地图地点与区划搜索并行：前者是「深圳市龙华区福城街道」这种整串的唯一出路 ——
  // 区划搜索按单级名字匹配，整串一个字也匹配不上
  if (q.length >= 2) {
    void searchPlacesFor(q);
  } else {
    placeSeq++;
    placeHits.value = [];
    placeSearching.value = false;
  }
  searchTimer = setTimeout(async () => {
    searching.value = true;
    try {
      // 带上门店（或设备）位置：同名的村全国到处都是，不带位置搜「福城」会先给新疆的
      const c = storeCenter.value;
      const r = await api.mRegionSearch(q,
        c ? { latE6: Math.round(c.lat * 1e6), lngE6: Math.round(c.lng * 1e6) } : undefined);
      hitRegions.value = r.regions.map((x) => ({
        regionCode: x.regionCode, parentCode: "", level: x.level, name: x.name,
        enabled: true, hasChild: x.level !== "STREET", path: x.path,
      } as Region & { path: string }));
      hitVillages.value = r.villages ?? [];
      hitCommunities.value = r.communities.map((x) => ({
        communityNo: x.communityNo, name: x.name, regionCode: x.regionCode ?? undefined, path: x.path,
      } as unknown as Community & { path: string }));
    } catch {
      // 搜索接口不在（老后端）：退回本地过滤，至少当前层还能搜
      hitVillages.value = [];
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

/**
 * 本级筛选（R11）。**只过滤当前这一屏，不发全局搜索** ——
 * 一个区几十个街道、一个街道上百个村，翻到底找一个名字比打两个字慢得多；
 * 而走全局搜索又会把人从「我正在广东省深圳市里挑」的上下文里踢出去。
 */
const levelFilter = ref("");
/** 行数少的时候不出这个框：三条街道还给个搜索框，只是多一行噪音 */
const FILTER_FROM = 12;
const levelFilterUseful = computed(() => {
  const n = atLeaf.value
    ? settleRows.value.length + villageRows.value.length + estates.value.length
    : list.value.length;
  return n >= FILTER_FROM || Boolean(levelFilter.value);
});
function matchFilter(name: string) {
  const f = levelFilter.value.trim();
  return !f || name.includes(f);
}
const levelRowsFiltered = computed(() => list.value.filter((r) => matchFilter(r.name)));
const settleRowsFiltered = computed(() => settleRows.value.filter((c) => matchFilter(c.name)));
const villageRowsFiltered = computed(() => villageRows.value.filter((v) => matchFilter(v.name)));
const estateRowsFiltered = computed(() => estates.value.filter((p) => matchFilter(p.name)));

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
 * 点一个小区：**当场开通并勾上**，没有提报、没有等待。
 *
 * 数据是高德给的（名字、门牌、坐标齐全），落哪个街道由服务端逆地理定夺 ——
 * 用「adcode + 街道名」而不是高德的 towncode：两套编码不同源，实测福城街道的
 * towncode 去掉后三位在统计局口径里是**观澜街道**，按码挂会静默挂错。
 *
 * 重复由服务端三道闸挡（官方村码 / 同街道归一名 / 坐标 150 米内且名字相近），
 * 撞上返回既有那条 —— 所以重复点同一个小区不会长出第二条。
 */
const opening = ref("");
async function useEstate(p: PlaceHit) {
  if (opening.value) return;
  opening.value = p.name;
  try {
    const c = await api.mOpenCommunityFromMap({
      name: p.name.slice(0, 30),
      address: p.address || undefined,
      latE6: Math.round(p.lat * 1e6),
      lngE6: Math.round(p.lng * 1e6),
      streetCode: atLeaf.value ? current.value?.regionCode : undefined,
    });
    if (!has("COMMUNITY", c.communityNo)) {
      emit("update:areas", [...props.areas, {
        level: "COMMUNITY" as ServiceArea["level"],
        refCode: c.communityNo,
        name: pathName(c.name),
      }]);
    }
    await ensureCommunitiesRefresh();
    uni.showToast({ title: t("store.picker.estateAdded"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error)?.message || t("store.applyFailed"), icon: "none" });
  } finally {
    opening.value = "";
  }
}

/**
 * 点一条搜出来的官方村：**不用先把面包屑走一遍**。
 *
 * 先把当前层挪到它所属的街道（提报单要挂在街道下），再走与街道内点村同一条路 ——
 * 名字去后缀、带上村码与坐标、官方村免审直接开通并自动勾上。
 */
async function useVillageHit(v: NonNullable<RegionSearchResult["villages"]>[number]) {
  keyword.value = "";
  const chain = await api.mRegionPath(v.streetCode).catch(() => [] as Region[]);
  if (chain.length) {
    trail.value = chain;
    await loadVillages(v.streetCode);
    void loadEstates(chain[chain.length - 1]?.name ?? "");
  }
  await useVillage({
    regionCode: v.regionCode,
    parentCode: v.streetCode,
    level: "VILLAGE",
    name: v.name,
    enabled: true,
    latE6: v.latE6,
    lngE6: v.lngE6,
  } as Region);
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

/* ================================================================
 * 统一结果列表（2026-08-23 重构）
 *
 * 此前这个面板长出了 8 类东西，而**同一个「福安雅园」从搜索框点和从街道列表点，
 * 行为还不一样**：前者提示「先走到这个地点所在的街道」再打开提报表单，后者直接加入。
 * 根源是把「街道」当成了用户必须先走到的地方 —— 可街道是行政编码，
 * 商家不知道也不需要知道，而系统能从坐标反查出来（adcode + 街道名）。
 * 系统自己有的信息，不该问用户要。
 *
 * 现在：**搜到什么，点一下就加什么**。三个来源（已开通聚落 / 官方村名录 / 地图 POI）
 * 合并成一个列表、同一种行样式 —— 来源差异对商家没有意义，他要的是「我做这个地方」。
 * 层级浏览降为「按行政区找」的次入口，它仍是「整个街道/整个区」的唯一入口。
 * ================================================================ */

type HitKind = "COMMUNITY" | "VILLAGE" | "POI" | "REGION";
interface Hit {
  key: string;
  kind: HitKind;
  name: string;
  /** 副标题：门牌地址或行政路径 */
  sub: string;
  communityNo?: string;
  regionCode?: string;
  streetCode?: string;
  level?: string;
  latE6?: number | null;
  lngE6?: number | null;
  address?: string;
  /** 区/市级覆盖要运营审核（街道自选即生效）—— 勾之前就要说清楚 */
  needsAudit?: boolean;
}

/** 层级浏览：默认关着，主路径是搜 */
const browsing = ref(false);

/** 门店附近的小区：空输入时的默认内容 —— 多数商家做的就是店周边那几个圈 */
const nearby = ref<PlaceHit[]>([]);
const nearbyLoading = ref(false);
const storeCenter = ref<{ lat: number; lng: number } | null>(null);

async function loadNearby() {
  if (nearbyLoading.value || nearby.value.length) return;
  nearbyLoading.value = true;
  try {
    // 门店坐标优先（他在店里给店周边配范围）；没标过点就退回设备定位
    if (!storeCenter.value) {
      const profile = await api.mStore().catch(() => null);
      if (profile?.latE6 != null && profile?.lngE6 != null) {
        storeCenter.value = { lat: profile.latE6 / 1e6, lng: profile.lngE6 / 1e6 };
      } else {
        const loc = await getLocation();
        if (loc) storeCenter.value = loc;
      }
    }
    const c = storeCenter.value;
    if (!c) return;
    const hits = await searchPlacesNear("小区", c, 3000);
    nearby.value = hits.filter((h) => looksLikeEstate(h.name) && !isOpened(h.name)).slice(0, 12);
  } finally {
    nearbyLoading.value = false;
  }
}

/** 已加入的（顶部清单用）。搜索时不过滤 —— 他要看的是「我已经选了什么」 */
const chosen = computed(() => props.areas);

/** 「浙江省 / 杭州市 / 西湖区」→「西湖区」。提示语里只需要末级，整条路径会把话挤没 */
function shortName(name?: string) {
  return (name ?? "").split(" / ").pop() ?? "";
}

/**
 * 这条覆盖项要不要等运营（R12）。**判据与后端保持同一句话**：
 * 小区/村、街道自助生效，区/市/省要审（MerchantStoreServiceImpl#selfEffective）。
 *
 * 后端回显时会带 `status`，但**新勾上还没保存的那几条没有** —— 那正是最需要提示的时刻：
 * 商家勾完整个市、关掉面板、以为立刻就能卖，实际要等审核。
 */
function areaPending(a: ServiceArea) {
  if (a.status) return a.status === "PENDING";
  return a.level !== "COMMUNITY" && a.level !== "STREET";
}

/**
 * 三路合并。**已经加入的不重复出现在候选里**，也不再按来源分组 ——
 * 对商家来说「库里有的」和「地图上找到的」是同一件事。
 */
const settleHits = computed<Hit[]>(() => {
  const out: Hit[] = [];
  const seen = new Set<string>();
  const push = (h: Hit) => {
    const k = normalizeName(h.name);
    if (!k || seen.has(k)) return;
    seen.add(k);
    out.push(h);
  };
  if (kw.value) {
    for (const c of hitCommunities.value) {
      push({ key: `c${c.communityNo}`, kind: "COMMUNITY", name: c.name,
        sub: c.path || c.address || "", communityNo: c.communityNo });
    }
    for (const v of hitVillages.value) {
      push({ key: `v${v.regionCode}`, kind: "VILLAGE", name: v.name, sub: v.path,
        regionCode: v.regionCode, streetCode: v.streetCode, latE6: v.latE6, lngE6: v.lngE6 });
    }
    for (const p of placeHits.value) {
      if (!looksLikeEstate(p.name)) continue;
      push({ key: `p${p.name}${p.address}`, kind: "POI", name: p.name, sub: p.address,
        latE6: Math.round(p.lat * 1e6), lngE6: Math.round(p.lng * 1e6), address: p.address });
    }
  } else {
    for (const c of communities.value) {
      push({ key: `c${c.communityNo}`, kind: "COMMUNITY", name: c.name,
        sub: c.address ?? "", communityNo: c.communityNo });
    }
    for (const p of nearby.value) {
      push({ key: `p${p.name}${p.address}`, kind: "POI", name: p.name, sub: p.address,
        latE6: Math.round(p.lat * 1e6), lngE6: Math.round(p.lng * 1e6), address: p.address });
    }
  }
  return out.filter((h) => !(h.kind === "COMMUNITY" && has("COMMUNITY", h.communityNo!)));
});

/**
 * 整片区域：省 / 市 / 区 / 街道。**排在小区之前**（R6）。
 *
 * 曾经排在后面，而上面那一段能出三十多条聚落 —— 手机上要滚很久才看得见它，
 * 于是「搜不到行政区」的结论就是这么来的（其实一直搜得到，只是在屏幕外）。
 */
const regionHits = computed<Hit[]>(() =>
  !kw.value ? [] : hitRegions.value.map((r) => ({
    key: `r${r.regionCode}`, kind: "REGION" as const, name: r.name, sub: r.path,
    regionCode: r.regionCode, level: r.level,
    // 街道自助生效；省/市/区要运营审 —— 差着量级，行上就得写明白
    needsAudit: r.level !== "STREET",
  })),
);

/**
 * 每段限高（R7）。一段吃掉整屏时，下面那几段就等于不存在 ——
 * 这正是上一版的病根，所以宁可先各露一点，再由人点开。
 */
const REGION_ROWS = 6;
const SETTLE_ROWS = 8;
const regionsOpen = ref(false);
const settlesOpen = ref(false);
watch(kw, () => {
  regionsOpen.value = false;
  settlesOpen.value = false;
});
const regionRows = computed(() => (regionsOpen.value ? regionHits.value : regionHits.value.slice(0, REGION_ROWS)));
const settleRowsHit = computed(() => (settlesOpen.value ? settleHits.value : settleHits.value.slice(0, SETTLE_ROWS)));

const adding = ref("");

/**
 * 点一行 = 立刻加入。**不弹表单、不跳页、不问街道**。
 *
 * 三种来源各自该做的事系统自己知道：库里有的直接勾；官方村与地图 POI 先开通再勾，
 * 而「开通」这一步商家看不见 —— 他要的只是「我做这个地方」。
 */
async function addHit(h: Hit) {
  if (adding.value) return;
  if (h.kind === "REGION") {
    // 区划不该被「点一下就整片加入」——那是另一个量级的决定。点它＝进去看下级
    void drillHit(h);
    return;
  }
  /*
   * 已经被某条区划盖住的聚落**不该再开一遍**：开通是有副作用的（建档、写台账），
   * 而加进去也是白加 —— 保存时会被父项吸收掉。先说清楚，别让人白点。
   */
  const cover = communityCoveredBy(h.streetCode ?? h.regionCode);
  if (cover) {
    uni.showToast({ title: t("store.picker.coveredBy", { s: cover.name.split(" / ").pop() ?? cover.name }), icon: "none" });
    return;
  }
  adding.value = h.key;
  try {
    let no = h.communityNo;
    let name = h.name;
    if (h.kind === "VILLAGE") {
      const a = await api.mApplyCommunity({
        name: cleanVillageName(h.name),
        regionCode: h.streetCode,
        kind: "VILLAGE",
        originCode: h.regionCode,
        latE6: h.latE6 ?? undefined,
        lngE6: h.lngE6 ?? undefined,
      });
      no = a.communityNo;
      name = a.name;
    } else if (h.kind === "POI") {
      const c = await api.mOpenCommunityFromMap({
        name: h.name.slice(0, 30),
        address: h.address || undefined,
        latE6: h.latE6!,
        lngE6: h.lngE6!,
      });
      no = c.communityNo;
      name = c.name;
    }
    if (!no) {
      uni.showToast({ title: t("store.applyFailed"), icon: "none" });
      return;
    }
    if (!has("COMMUNITY", no)) {
      // 记下它挂的街道码：将来勾「整个这条街道」时要靠它把这条收掉
      const street = h.streetCode ?? h.regionCode;
      if (street) areaRegion.value[no] = street;
      addArea({ level: "COMMUNITY" as ServiceArea["level"], refCode: no, name });
    }
    await ensureCommunitiesRefresh();
    uni.showToast({ title: t("store.picker.estateAdded"), icon: "none" });
  } catch (e) {
    // 失败原因要原样说出来：「已经开通了，直接勾选即可」这类提示比一句「失败」有用得多
    uni.showToast({ title: (e as Error)?.message || t("store.applyFailed"), icon: "none" });
  } finally {
    adding.value = "";
  }
}

/**
 * 点搜索结果里的一条**区划**：进到那一级的列表里去，与逐级点下来的结果一致。
 *
 * <p><b>不是「整片加入」</b>：搜「福城」多半是想看这个街道底下有哪些小区，
 * 而不是把整条街道一次性框进经营范围 —— 后者动辄几十个小区、还要运营审。
 * 整片加入留给行右侧那个勾选框，是个明确的、第二位的动作。
 */
async function drillHit(h: Hit) {
  levelFilter.value = "";
  const chain = await api.mRegionPath(h.regionCode!).catch(() => [] as Region[]);
  keyword.value = "";
  browsing.value = true;
  trail.value = chain.length ? chain : trail.value;
  const cur = trail.value[trail.value.length - 1];
  if (!cur) return;
  if (cur.level === "STREET") {
    await loadVillages(cur.regionCode);
    void loadEstates(cur.name);
  } else {
    await loadLevel(cur.regionCode);
  }
}

/** 从顶部清单里删一条。地图/搜索误点很容易，必须有后悔的地方 */
function removeArea(a: ServiceArea) {
  emit("update:areas", props.areas.filter((x) => !(x.level === a.level && x.refCode === a.refCode)));
}

const chosenOpen = ref(false);

function close() {
  emit("update:visible", false);
}
</script>

<template>
  <view v-if="visible" class="mask" @tap="close">
    <view class="sheet" @tap.stop>
      <!--
        已选摘要在**标题栏右侧**而不是面板里单独占一行：那一行会被列表挤出视野，
        而「我到底选了几条」是这一屏从头到尾都要能看见的东西。
      -->
      <view class="sheet__head">
        <text class="sheet__title">{{ $t("store.picker.title") }}</text>
        <view v-if="chosen.length" class="sheet__sel" @tap="chosenOpen = !chosenOpen">
          <text class="sheet__selT">{{ $t("store.picker.selected", { n: areas.length }) }}</text>
          <text class="sheet__selM">{{ chosenOpen ? $t("store.picker.collapse") : $t("store.picker.expand") }}</text>
        </view>
        <text v-else class="sheet__count">{{ $t("store.picker.selected", { n: areas.length }) }}</text>
      </view>

      <view class="search">
        <sh-icon name="search" :size="18" color="var(--sh-sub)"></sh-icon>
        <input v-model="keyword" class="search__input" :maxlength="20" :placeholder="$t('store.picker.searchPh2')" />
      </view>

      <!-- 已选清单：误点很容易，必须有个当场能删的地方。开关在标题栏右侧 -->
      <view v-if="chosen.length && chosenOpen" class="chosen">
        <view class="chosen__list">
          <view v-for="a in chosen" :key="a.level + a.refCode" class="chosen__row">
            <text class="chosen__name">{{ a.name }}</text>
            <!-- 待审的要在**已选清单里**看得见：只写在搜索行上的话，勾完就再也看不到了 -->
            <text v-if="areaPending(a)" class="chosen__audit">{{ $t("store.picker.pendingTag") }}</text>
            <text class="chosen__del" @tap="removeArea(a)">{{ $t("common.remove") }}</text>
          </view>
        </view>
      </view>

      <!--
        面包屑**常驻**（搜索时也在）：不然搜索一开口就不知道自己站在哪一级，
        而「我正在深圳市里挑」正是这一屏最要紧的上下文。
      -->
      <view class="crumb">
        <text class="crumb__i" :class="{ 'is-cur': browsing && !trail.length }" @tap="backTo(-1)">{{ $t("store.regionRoot") }}</text>
        <text v-for="(x, i) in trail" :key="x.regionCode" class="crumb__i" :class="{ 'is-cur': browsing && i === trail.length - 1 }" @tap="backTo(i)">
          › {{ x.name }}
        </text>
        <!-- 搜索时路径灰着（它说明的是「回去之后会落在哪」），点一下就回到浏览 -->
        <text v-if="!browsing && (kw || trail.length)" class="crumb__back" @tap="backTo(trail.length - 1)">{{ $t("store.picker.browseHere") }}</text>
      </view>

      <!--
        「整个 XX」是每一级的第一行，固定在列表上方：
        它让「我就要这一整片」与「我进去挑几个」在同一屏里并列，不用先决定走哪条路。
        搜索态不出现 —— 那时列表里不是本级的下级，一个「整个」会指错对象。
      -->
      <view v-if="browsing && current" class="whole" :class="{ 'is-on': has(current.level, current.regionCode) }" @tap="toggleRegion(current)">
        <text class="whole__t">{{ $t("store.picker.wholeLevel", { s: current.name }) }}</text>
        <text v-if="has(current.level, current.regionCode)" class="whole__on">{{ $t("store.picker.picked") }}</text>
        <text v-else-if="current.level !== 'STREET'" class="whole__audit">{{ $t("store.picker.needAudit") }}</text>
      </view>

      <scroll-view scroll-y class="body" :scroll-into-view="scrollInto" scroll-with-animation>
        <!-- ---------------- 层级浏览态 ---------------- -->
        <template v-if="browsing">
          <text v-if="loading" class="hint">{{ $t("common.loading") }}</text>
          <template v-else>
            <!-- 本级筛选（R11）：一个区几十个街道、一个街道上百个村，翻不如打两个字 -->
            <view v-if="!loading && levelFilterUseful" class="filter">
              <input
                v-model="levelFilter"
                class="filter__i"
                :placeholder="$t('store.picker.filterHere', { s: current ? current.name : $t('store.regionRoot') })"
                confirm-type="search"
              />
              <text v-if="levelFilter" class="filter__x" @tap="levelFilter = ''">✕</text>
            </view>

            <view v-for="r in (atLeaf ? [] : levelRowsFiltered)" :key="r.regionCode" class="row"
                  :class="{ 'is-covered': coveredBy(r.regionCode) }">
              <view class="row__main" @tap="drill(r)">
                <text class="row__name">{{ r.name }}</text>
                <text v-if="coveredBy(r.regionCode)" class="row__sub">
                  {{ $t("store.picker.coveredTag", { s: shortName(coveredBy(r.regionCode)?.name) }) }}
                </text>
              </view>
              <view class="row__check" :class="{ 'is-on': has(r.level, r.regionCode), 'is-off': !!coveredBy(r.regionCode) }" @tap="toggleRegion(r)">
                <text v-if="has(r.level, r.regionCode)" class="row__tick">✓</text>
              </view>
              <!--
                竖线把两个点击区分开。**这不是装饰**：左边是「把整片加进来」（勾一个市影响几千个小区），
                右边只是「换一屏」—— 后果差着量级的两个动作挨在一起，手一滑就是一条待审记录。
              -->
              <view v-if="r.hasChild || r.level === 'STREET'" class="row__sep"></view>
              <sh-icon v-if="r.hasChild || r.level === 'STREET'" name="chevronRight" :size="18" color="var(--sh-sub)" @tap="drill(r)"></sh-icon>
            </view>

            <!-- 街道里的聚落与小区：点击行为与搜索结果**完全一致** -->
            <template v-if="atLeaf">
              <view v-for="c in settleRowsFiltered" :key="c.communityNo" class="row"
                    :class="{ 'is-covered': communityCoveredBy(c.regionCode) }" @tap="toggleCommunity(c)">
                <view class="row__main">
                  <text class="row__name">{{ c.name }}</text>
                  <text v-if="communityCoveredBy(c.regionCode)" class="row__sub">
                    {{ $t("store.picker.coveredTag", { s: shortName(communityCoveredBy(c.regionCode)?.name) }) }}
                  </text>
                  <text v-else-if="c.address" class="row__sub">{{ c.address }}</text>
                </view>
                <view class="row__check" :class="{ 'is-on': has('COMMUNITY', c.communityNo) }">
                  <text v-if="has('COMMUNITY', c.communityNo)" class="row__tick">✓</text>
                </view>
              </view>
              <view v-for="v in villageRowsFiltered" :key="v.regionCode" class="place"
                    @tap="addHit({ key: 'v' + v.regionCode, kind: 'VILLAGE', name: v.name, sub: '', regionCode: v.regionCode, streetCode: current?.regionCode, latE6: v.latE6, lngE6: v.lngE6 })">
                <view class="place__main">
                  <text class="place__name">{{ v.name }}</text>
                  <text class="place__addr">{{ v.latE6 != null ? $t("store.picker.villageLocated") : $t("store.picker.villageHint") }}</text>
                </view>
                <text class="place__go">{{ adding === 'v' + v.regionCode ? "…" : $t("store.picker.estateAdd") }}</text>
              </view>
              <view v-for="p in estateRowsFiltered" :key="p.name + p.address" class="place"
                    @tap="addHit({ key: 'p' + p.name + p.address, kind: 'POI', name: p.name, sub: p.address, latE6: Math.round(p.lat * 1e6), lngE6: Math.round(p.lng * 1e6), address: p.address })">
                <view class="place__main">
                  <text class="place__name">{{ p.name }}</text>
                  <text v-if="p.address" class="place__addr">{{ p.address }}</text>
                </view>
                <text class="place__go">{{ adding === 'p' + p.name + p.address ? "…" : $t("store.picker.estateAdd") }}</text>
              </view>
            </template>
          </template>
        </template>

        <!-- ---------------- 搜索 / 附近（主路径） ---------------- -->
        <template v-else>
          <!-- 整片区域排在最前：省 / 市 / 区 / 街道。点行进下级，点勾整片加入 -->
          <template v-if="regionHits.length">
            <text class="group">{{ $t("store.picker.regionGroup") }}</text>
            <view v-for="h in regionRows" :key="h.key" class="place" :class="{ 'is-covered': coveredBy(h.regionCode ?? '') }">
              <view class="place__main" @tap="drillHit(h)">
                <text class="place__name">{{ h.name }}</text>
                <text class="place__addr">
                  {{ h.sub }}{{ coveredBy(h.regionCode ?? '')
                    ? " · " + $t("store.picker.coveredTag", { s: shortName(coveredBy(h.regionCode ?? '')?.name) })
                    : h.needsAudit ? " · " + $t("store.picker.needAudit") : "" }}
                </text>
              </view>
              <!-- 主动作是「进去看下级」；整片加入是右边这个勾，第二位的动作 -->
              <view
                class="row__check"
                :class="{ 'is-on': has(h.level ?? '', h.regionCode ?? ''), 'is-off': !!coveredBy(h.regionCode ?? '') }"
                @tap.stop="toggleRegion({ regionCode: h.regionCode ?? '', level: h.level ?? '', name: h.name, parentCode: '', enabled: true, hasChild: true, path: h.sub })"
              >
                <text v-if="has(h.level ?? '', h.regionCode ?? '')" class="row__tick">✓</text>
              </view>
              <view class="row__sep"></view>
              <sh-icon name="chevronRight" :size="18" color="var(--sh-sub)" @tap="drillHit(h)"></sh-icon>
            </view>
            <view v-if="!regionsOpen && regionHits.length > regionRows.length" class="more" @tap="regionsOpen = true">
              <text class="more__t">{{ $t("store.picker.more", { n: regionHits.length - regionRows.length }) }}</text>
            </view>
          </template>

          <text v-if="!kw && nearbyLoading" class="hint">{{ $t("common.loading") }}</text>
          <text v-else-if="!kw && !settleHits.length" class="hint">{{ $t("store.picker.nearbyEmpty") }}</text>
          <text v-else-if="!kw" class="group">{{ $t("store.picker.nearbyTitle") }}</text>

          <!-- 小区 / 村：三个来源同一种行样式。来源差异对商家没有意义 -->
          <template v-if="kw && settleHits.length">
            <text class="group">{{ $t("store.picker.settleGroup") }}</text>
          </template>
          <view v-for="h in settleRowsHit" :key="h.key" class="place" @tap="addHit(h)">
            <view class="place__main">
              <text class="place__name">{{ h.name }}</text>
              <text v-if="h.sub" class="place__addr">{{ h.sub }}</text>
            </view>
            <text class="place__go">{{ adding === h.key ? "…" : $t("store.picker.estateAdd") }}</text>
          </view>
          <view v-if="!settlesOpen && settleHits.length > settleRowsHit.length" class="more" @tap="settlesOpen = true">
            <text class="more__t">{{ $t("store.picker.more", { n: settleHits.length - settleRowsHit.length }) }}</text>
          </view>

          <!--
            空输入时**两条路并列**：上面是门店附近的小区（多数商家要的就是这几个），
            下面直接给省级列表 —— 走快递、做全省的那批人不用先去找一行小字入口。
          -->
          <template v-if="!kw && list.length">
            <text class="group">{{ $t("store.picker.fromRegion") }}</text>
            <view v-for="r in list" :key="'top' + r.regionCode" class="row" @tap="drill(r)">
              <view class="row__main">
                <text class="row__name">{{ r.name }}</text>
              </view>
              <view class="row__check" :class="{ 'is-on': has(r.level, r.regionCode) }" @tap.stop="toggleRegion(r)">
                <text v-if="has(r.level, r.regionCode)" class="row__tick">✓</text>
              </view>
              <view class="row__sep"></view>
              <sh-icon name="chevronRight" :size="18" color="var(--sh-sub)"></sh-icon>
            </view>
          </template>

          <!-- 一个字时只搜得到区划：说清楚，别让人以为「这地方没有」 -->
          <text v-if="kw.length === 1 && !searching" class="hint">{{ $t("store.picker.oneCharHint") }}</text>
          <text v-else-if="kw && !settleHits.length && !regionHits.length && !searching && !placeSearching" class="hint">
            {{ $t("store.picker.searchEmpty") }}
          </text>

          <!--
            「按行政区找」的入口**只在搜不到时留着**：空输入时省级列表就在上面那一段，
            再放一行小字等于同一件事说两遍。
          -->
          <view v-if="kw && !settleHits.length && !regionHits.length" class="row row--apply" @tap="browsing = true">
            <text class="row__apply">{{ $t("store.picker.browseEntry") }}</text>
          </view>
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
  color: var(--sh-ink);
}
.place__addr {
  font-size: 24rpx;
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
  color: var(--sh-ink);
}
.apply__poi-addr {
  font-size: 24rpx;
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

/* 被上级覆盖的行：能看见、点得动（点了会说明原因），但明显是「不用再选」的样子 */
.row.is-covered,
.place.is-covered {
  opacity: 0.55;
}
.row__check.is-off {
  border-color: var(--sh-line);
  background: var(--sh-faint);
}

/* 每段的「还有 N 条」。不是分页 —— 一次点开全部，商家不需要理解页码 */
.more {
  padding: 20rpx 24rpx;
}
.more__t {
  font-size: 26rpx;
  color: var(--sh-primary-text);
}

/* 本级筛选框：只过滤当前这一屏 */
.filter {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin: 8rpx 24rpx 4rpx;
  padding: 0 20rpx;
  height: 68rpx;
  border-radius: 44rpx;
  background: var(--sh-faint);
}
.filter__i {
  flex: 1;
  font-size: 26rpx;
  color: var(--sh-ink);
}
.filter__x {
  font-size: 24rpx;
  color: var(--sh-sub);
  padding: 0 8rpx;
}

/* 已选清单里的待审标：与行内那句「选中后需运营审核」是同一件事的两个时刻 */
.chosen__audit {
  margin-right: 16rpx;
  font-size: 24rpx;
  color: var(--sh-warning);
}

/* 两个点击区之间的竖线。左＝整片加入、右＝进下级，后果差着量级，不能挨着 */
.row__sep {
  width: 1rpx;
  height: 36rpx;
  margin: 0 20rpx 0 12rpx;
  background: var(--sh-line);
}

/* 已选摘要挪到标题栏右侧：滚多远都看得见「我选了几条」，点一下就地展开 */
.sheet__sel {
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.sheet__selT {
  font-size: 26rpx;
  color: var(--sh-primary-text);
}
.sheet__selM {
  font-size: 24rpx;
  color: var(--sh-sub);
}
</style>
