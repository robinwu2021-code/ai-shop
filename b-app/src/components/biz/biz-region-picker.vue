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
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { pickOnMap, regionCenter } from "@/utils/geo";
import { getLocation } from "@shared/ports/location";
import type { PlaceHit } from "@shared/ports/geo-search";
import type { Community, Region, RegionSearchResult, ServiceArea } from "@shared/types";

const props = defineProps<{
  visible: boolean;
  areas: ServiceArea[];
}>();
const emit = defineEmits<{
  (e: "update:visible", v: boolean): void;
  (e: "update:areas", v: ServiceArea[]): void;
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
 * 再往下一层：**村/社区里的小区**。
 *
 * 城区一个居委会底下好几个小区，而商家心里的「我做哪儿」是具体那个小区 ——
 * 停在「西坑社区」等于把范围放大了一圈，他的货会出现在隔壁小区的买家面前。
 * 这一层的数据只能来自地图：官方名录的第五级就是居委会本身，没有再下一级。
 */
const atVillage = computed(() => current.value?.level === "VILLAGE");

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
 * 设备定位／已解析出的圆心。**只当排序与偏好用**，不再是「能不能查到」的前提 ——
 * 圆心解不出来时服务端会用 `addressPath` 兜底地理编码，不会一路退化成盲搜。
 */
const scopeCenter = ref<{ lat: number; lng: number } | null>(null);

/**
 * 这个街道 / 村·社区下的**小区**（地图来源，服务端读穿透）。
 *
 * 为什么不能只给官方名录：名录的第五级是**居委会/村委会**，城区一个居委会底下好几个小区，
 * 而商家心里的「我做哪儿」是具体那个小区 —— 只能选到居委会等于把范围放大了一圈，
 * 他的货会出现在隔壁小区的买家面前，而那些人他根本送不到。
 * 农村的村委会≈村，够用；城区必须补这一层，数据只能来自地图。
 *
 * **v5：App 不再自己调原生 SDK 拼结果**。此前是端上问高德、写回缓存，
 * 出过一次真事故（V206：端上写的缓存键和服务端读的键对不上，缓存一直没生效，
 * 失败又被两边的静默 catch 一起吞掉）。现在读写都在 `/biz/geo/estates` 这一个
 * 服务端接口里做完：缓存新鲜直接给，缺失或过期就服务端自己去问地图、自己写缓存。
 * App 只有打开「在地图上选点」这个真正的地图 UI 时才碰原生 SDK。
 */
const estates = ref<PlaceHit[]>([]);
const estatesLoading = ref(false);

/**
 * 当前停在的这一层如果是**已开通的社区/村**，记着它是哪条 ——
 * 「整个西坑社区」这时候该勾的就是它本身，而不是照着名字再建一条新的。
 */
const openedContainer = ref<Community | null>(null);

/** 当前村/社区一片的小区。空列表不是错：农村的村委会≈村，本来就没有小区 */
const villageEstates = ref<PlaceHit[]>([]);
const villageEstatesLoading = ref(false);

/**
 * 上一级下辖各片的小区条数（来自缓存表）。
 *
 * **每一行的 › 都得有依据**：没有这个数，城乡两边都只能给一个「点进去才知道空不空」的箭头。
 * 抓过的直接写「12 个小区」，没抓过的写「点右侧 › 看这一片的小区」——
 * 后者承诺的是「去看看」，不是「有」。
 */
const estateCounts = ref<Record<string, number>>({});
async function loadEstateCounts(parentCode: string) {
  try {
    estateCounts.value = await api.mEstateCounts(parentCode);
  } catch {
    estateCounts.value = {};
  }
}

/**
 * 这一片是不是**已经问过、且确定是空的**。
 *
 * 只有这个成立才把 › 收回去 —— 第一次谁也不知道有没有，drill 一下是唯一的了解方式；
 * 但已经知道是 0 条之后再给一个 ›，就是逼人点一次「已知是空」的按钮，见一次空列表，
 * 才后知后觉地明白「原来没有」。已经知道的事不该让人重新走一遍才能确认。
 */
function knownEmpty(code: string) {
  return estateCounts.value[code] === 0;
}

function estateNote(code: string) {
  const n = estateCounts.value[code];
  if (n == null) return t("store.picker.villageDrill");
  return n > 0 ? t("store.picker.estateCount", { n }) : t("store.picker.estateNone");
}

/**
 * 这一片在缓存里的键。
 *
 * **已开通的社区不能用它的 regionCode**：那是它挂的**街道**码，
 * 与街道自己那一片撞键 —— 进「茜坑社区」会读到整个街道的结果，反过来也一样。
 */
function estateScope(v: Region, container?: Community | null) {
  return container ? `C${container.communityNo}` : v.regionCode;
}

/**
 * 一片的小区：**一次调用**，服务端自己判断缓存新鲜不新鲜、要不要现问地图。
 *
 * 圆心优先级：已开通社区/官方村自带的坐标（最准）› 本次会话已经算出的设备定位
 * （`scopeCenter`，排序用，未必落在这一片正中间）› 都没有就把 `addressPath` 交给
 * 服务端，让它用地理编码兜底 —— 三档都拿不到时服务端只能原样返回旧缓存。
 */
async function fetchScopedEstates(scopeCode: string, parentCode: string,
                                  known?: { latE6?: number | null; lngE6?: number | null; rural?: boolean }): Promise<PlaceHit[]> {
  /*
   * **不拿设备定位当这一片的圆心**。`scopeCenter` 是「我这台测试手机现在在哪儿」，
   * 跟「牛杜镇在哪儿」毫无关系 —— 真机上撞过：牛杜镇（山西）自己没有坐标，
   * 一旦拿设备定位顶上去，搜出来的是测试手机所在的成都的小区。
   * 没有已知坐标就把 `addressPath` 留给服务端地理编码（山西省 / 运城市 / … / 牛杜镇），
   * 那才是这一片真正的位置。scopeCenter 只用于 mRegionSearch 排序和地图选点的初始位置。
   */
  const latE6 = known?.latE6 ?? undefined;
  const lngE6 = known?.lngE6 ?? undefined;
  try {
    const res = await api.mEstates(scopeCode, {
      parentCode,
      latE6: latE6 ?? undefined,
      lngE6: lngE6 ?? undefined,
      addressPath: trail.value.map((r) => r.name).join(" / "),
      city: cityName.value,
      rural: known?.rural,
    });
    estateCounts.value = { ...estateCounts.value, [scopeCode]: res.items.length };
    return res.items
      .filter((it) => it.latE6 != null && it.lngE6 != null)
      .map((it) => ({ name: it.name, address: it.address ?? "", city: "",
        lat: it.latE6! / 1e6, lng: it.lngE6! / 1e6 }));
  } catch {
    return [];
  }
}

/**
 * 街道这一片的小区。**必须把街道自己的已知坐标带上**（若有）——
 * 漏了这一步的话，服务端「有坐标就不查」那条近路永远用不上：查过一次、
 * 服务端也顺手把坐标存回了 `sys_region`，但这里不传，下次进同一条街道还是会
 * 老老实实走一遍地址地理编码（方案二：坐标按需补全）。
 */
async function loadEstates(street: Region) {
  estatesLoading.value = true;
  estates.value = [];
  try {
    const parent = trail.value[trail.value.length - 2]?.regionCode ?? "";
    estates.value = await fetchScopedEstates(street.regionCode, parent,
      { latE6: street.latE6, lngE6: street.lngE6 });
  } finally {
    estatesLoading.value = false;
  }
}

async function loadVillageEstates(v: Region, container?: Community | null) {
  villageEstates.value = [];
  villageEstatesLoading.value = true;
  try {
    const scope = estateScope(v, container);
    const parent = (container ? container.regionCode : trail.value[trail.value.length - 2]?.regionCode) ?? "";
    /*
     * 城乡搜法不一样（见后端 EstateCacheService#resolve 的注释），判据读服务端存的
     * `rural` 字段（sys_region.rural），不解析名字——**这一行理论上不会被村委会
     * 触发**：hasChild 已经把村委会挡在了外面，drill 不到这里；这里的 rural 多半
     * 总是 false（社区/居委会），留着只是为了服务端逻辑与「万一以后有例外」兜底。
     */
    const rural = !!(container?.rural ?? v.rural);
    villageEstates.value = await fetchScopedEstates(scope, parent, { latE6: v.latE6, lngE6: v.lngE6, rural });
  } finally {
    villageEstatesLoading.value = false;
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

/**
 * 这一条是不是**还装着小区**的容器（社区/居委会/村），而不是具体某个小区。
 *
 * 城区的「西坑社区」底下是好几个小区，停在它上面等于把范围放大一圈；
 * 而「福安雅园 A 区」本身就是终点。名字与 kind 都认：库里 kind 不是每条都填了。
 */
function looksLikeContainer(name: string, kind?: string) {
  return kind === "VILLAGE" || /(社区|居委会|村委会|村)$/.test(name);
}

/**
 * 第五级（村/社区）**一律可以再看一层**，城乡同一条规则。
 *
 * 曾经按「有没有坐标」和「是村还是社区」分别开闸：农村的村委会不给 ›、
 * 没补录坐标的不给 › —— 结果是同一种东西在两个地方长得不一样，
 * 商家得先学会「哪些点得进去」。差别应该只体现在**数据多少**上：
 * 城区进去十几个小区，农村进去零个（那就明说，并且顶部永远能整片勾）。
 */
const VILLAGE_DRILLABLE = true;

/** 已开通的聚落按 name 去重用：官方村里已经开通过的，不再重复列一条「去提报」 */
const openedNames = computed(
  () => new Set(communities.value.filter((c) => c.regionCode === current.value?.regionCode).map((c) => c.name)),
);
/** 「阳光花园」「阳光花园小区」「阳光花园(北区)」在商家嘴里是同一个地方 —— 与后端同一套归一 */
function normalizeName(s: string) {
  /*
   * 「村委会」必须单独列出，不能指望「村」+「委会」拼出来 —— 「委会」不在词表里，
   * 漏了这一条会让「景滑村委会」（官方机构名）穿过归一化，跟「景滑」（商家起的名）
   * 判成两个不同的地方（真机上搜「景滑村」出过两条，服务端 PlaceNames 同一处也补了）。
   */
  return s.replace(/[（(].*?[）)]/g, "")
    .replace(/(小区|花园|家园|新村|苑|园|村委会|村|社区|居委会|村民委员会|居民委员会)+$/g, "")
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
  /*
   * 后台取一次设备位置当默认圆心。**不 await** —— 它只影响排序与「先在附近找」，
   * 没有也能搜。有了之后村级搜索能先走带坐标的那条（线上实测 5 毫秒，
   * 而全表按名字扫是 2 秒），同名的「福城街道」也能按远近排。
   */
  if (!scopeCenter.value) {
    void getLocation().then((c) => { if (c && !scopeCenter.value) scopeCenter.value = { lat: c.lat, lng: c.lng }; })
      .catch(() => { /* 没授权定位不影响搜索 */ });
  }
  trail.value = [];
  tab.value = "REGION";
  chosenOpen.value = false;
  resetSearch();
  await Promise.all([loadLevel(undefined), ensureCommunities()]);
}

/**
 * 搜索态归零。**每次打开选择器都要做** —— 状态挂在组件上，而组件不随面板关闭销毁：
 * 上次搜「福安」留下的一屏结果会原样出现在下一次打开时，而那时他要配的多半是另一个城市。
 */
function resetSearch() {
  q.value = "";
  hits.value = null;
  mapHits.value = [];
  clearTimeout(searchTimer);
}

watch(() => props.visible, (v) => { if (v) void open(); });

async function drill(r: Region, container?: Community | null) {
  if (!r.hasChild && r.level !== "STREET" && r.level !== "VILLAGE") return;
  trail.value = [...trail.value, r];
  await enterLevel(r, container);
}

async function backTo(i: number) {
  trail.value = trail.value.slice(0, i + 1);
  const cur = trail.value[i];
  if (!cur) {
    await loadLevel(undefined);
    return;
  }
  await enterLevel(cur);
}

/** 停在某一级要加载什么：区划取下一级、街道取聚落、村取这一片的小区 */
async function enterLevel(r: Region, container?: Community | null) {
  openedContainer.value = container ?? null;
  if (r.level === "STREET") {
    void loadEstateCounts(r.regionCode);
    await loadVillages(r.regionCode);
    void loadEstates(r);
  } else if (r.level === "VILLAGE") {
    await loadVillageEstates(r, container);
  } else {
    await loadLevel(r.regionCode);
  }
}

// ---------------------------------------------------------------- 选中
function has(level: string, refCode: string) {
  return props.areas.some((a) => a.level === level && a.refCode === refCode);
}

/** 这一行加进来之后还在不在已选里（取消勾选后要跟着变回未选） */
function hitPicked(key: string) {
  const no = hitAdded.value[key];
  return !!no && has("COMMUNITY", no);
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
  // 自己已经在清单里就不说「被覆盖」：两个状态同时挂在一行上，读的人不知道该信哪个
  if (props.areas.some((a) => a.refCode === regionCode)) return null;
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

/** 行上的覆盖提示：自己已被勾中时不显示 —— 勾与「被覆盖」是互斥的两种说法 */
function coverNote(r: { picked: boolean; covered: ServiceArea | null }) {
  return r.picked ? null : r.covered;
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
   * **所有粒度自选即生效**（2026-08-24 起，取代 R13 的「省/市/区先问一句」）。
   * 后端同一天把 ADR-013 §4.2 的审核闸拿掉了（MerchantStoreServiceImpl#replaceAreas）——
   * 两边必须同步：只拿掉这边的确认框、后端还在走 PENDING 的话，商家会以为
   * 「已经生效」而实际上买家端看不到，比留着确认框更糟。
   */
  addArea({ level: r.level as ServiceArea["level"], refCode: r.regionCode, name });
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

/** 本街道下已开通的聚落。**只按街道过滤**，没有第二种口径 */
const settleRows = computed<Array<Community & { path?: string }>>(() => {
  if (!atLeaf.value) return [];
  return communities.value.filter((c) => c.regionCode === current.value?.regionCode);
});

// ---------------------------------------------------------------- 地图兜底：搜到即加
/** 「富城村村民委员会」→「富城村」：聚落叫的是地名，不是机构名 */
function cleanVillageName(official: string): string {
  /*
   * 官方名是**机构名**（「牛杜村委会」「茜坑社区居委会」），而商家嘴里是**地名**
   * （「牛杜村」「茜坑社区」）。去掉的只是「委员会」那一截，**地名的通名要留着** ——
   * 此前一并吃掉，牛杜村委会变成了「牛杜」，而搜索里显示「牛杜」、名录里显示
   * 「牛杜村委会」、已开通里又是「牛杜村」，同一个地方三种写法，看着像三层。
   */
  const cleaned = official
    .replace(/(村民委员会|村委会)$/, "村")
    .replace(/(居民委员会|居委会)$/, "社区")
    .replace(/委员会$/, "")
    // 「富城村村民委员会」→「富城村村」：通名重了收掉一个
    .replace(/村村$/, "村")
    .replace(/社区社区$/, "社区");
  return cleaned || official;
}

/**
 * 市名给高德缩范围：优先取面包屑第二级（已经在浏览某个市），
 * 没有面包屑（在根级直接搜）时，从**关键词自己**里抠一个市名出来 ——
 * 「深圳市龙华区福安雅园」这种带完整地址的写法，「深圳市」就在词里，
 * 不抠出来的话原生 SDK 的 poiSearchInCity 拿到的 city 是空字符串，
 * 退化成全国搜「福安雅园」，同名的、更有名的候选会把真正要的那条挤下去。
 */
const cityName = computed(() => trail.value.find((r) => r.level === "CITY")?.name ?? guessCityFrom(q.value));

const CITY_SUFFIXES = ["市", "自治州", "地区", "盟"];
/** 「深圳市龙华区福安雅园」→「深圳市」。只认省市这两级前缀 */
function guessCityFrom(kw: string): string | undefined {
  for (const suf of CITY_SUFFIXES) {
    const i = kw.indexOf(suf);
    if (i > 0 && i <= 6) return kw.slice(0, i + suf.length);
  }
  return undefined;
}

/**
 * 在地图上选点 —— 选完**当场开通并勾上**，没有表单、没有提报、没有等待（v4）。
 *
 * 原本这里通向一张提报表单（名称/门牌/坐标 + 运营审核）。它是「地图拿不到坐标」时代的产物：
 * 那时提报单里的坐标是「提交那一刻的设备定位」，尽力而为还可能为空。现在两条地图路径
 * （联想命中、地图选点）都带名字、门牌与坐标，`from-map` 能直接建档，
 * 再留一个「提报」等于让商家在「直接加」和「等三天多半被驳回」之间做选择。
 *
 * 初始中心按「离要标的点最近」排：**当前所在层的中心** › 设备定位。店主常在自己店里
 * 给另一个区配范围，开局落在脚下等于每次先把地图拖几百公里。
 */
const picking = ref(false);
async function pickOnMapAndAdd() {
  if (picking.value) return;
  picking.value = true;
  try {
    const init = await regionCenter(trail.value.map((r) => r.name))
      ?? (await getLocation().catch(() => null));
    const p = await pickOnMap(t, init ? { lat: init.lat, lng: init.lng } : null);
    if (!p) return;
    await addHit({
      key: `m${p.lat},${p.lng}`,
      kind: "POI",
      name: p.name || t("store.picker.mapUnnamed"),
      sub: p.address ?? "",
      // 街道由服务端逆地理定夺 —— 商家在哪一层选的点不代表那个点属于哪个街道
      latE6: Math.round(p.lat * 1e6),
      lngE6: Math.round(p.lng * 1e6),
      address: p.address,
    });
  } finally {
    picking.value = false;
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

/** 已加入的（顶部清单用）。搜索时不过滤 —— 他要看的是「我已经选了什么」 */
const chosen = computed(() => props.areas);

/** 「浙江省 / 杭州市 / 西湖区」→「西湖区」。提示语里只需要末级，整条路径会把话挤没 */
function shortName(name?: string) {
  return (name ?? "").split(" / ").pop() ?? "";
}

/**
 * 这条覆盖项是不是还挂着旧的待审状态。
 *
 * **2026-08-24 起所有粒度自选即生效**，新勾上的（还没有 `status`）不再当成待审——
 * 之前这里会把没有 `status` 的省/市/区猜成待审，那是给「新勾的条目还没保存」兜底；
 * 现在新勾的条目本来就不需要审，猜的意义没了。留着只读 `status === PENDING`，
 * 是为了兼容审核闸拿掉之前就已经存在的存量待审记录——那些要等商家自己重新保存一次
 * （后端会把它们一并转成 ACTIVE），或者运营那边处理完，标签才会消失。
 */
function areaPending(a: ServiceArea) {
  return a.status === "PENDING";
}

/**
 * **一层一行，每层同一种行**。
 *
 * 此前区划走 `.row`、聚落走 `.place`、地图地点又是第三种样式：同一件事
 * （「把这个地方加进我的经营范围」）在不同层里长成三个样子，商家得在每一层
 * 重新学一遍哪儿能点。这里把三类来源合成同一个 Row —— 左边勾选＝整片加入，
 * 右边 › ＝进下一级；最后一级没有下级，于是只有勾选。
 */
interface Row {
  key: string;
  name: string;
  sub?: string;
  /** 有下级就给 ›。街道恒为 true —— 它的下级是聚落，不是区划 */
  hasChild: boolean;
  picked: boolean;
  /** 被哪条已选项盖住了（父项覆盖子项），null = 没被盖 */
  covered: ServiceArea | null;
  region?: Region;
  community?: Community;
  hit?: Hit;
  /** 来自搜索：下钻前要先补面包屑 */
  fromSearch?: boolean;
}

const rows = computed<Row[]>(() => {
  if (atVillage.value) {
    const v = current.value!;
    const street = trail.value[trail.value.length - 2]?.regionCode;
    const short = cleanVillageName(v.name);
    const out: Row[] = [];
    /*
     * 这一片**已经开通**的小区排在前面：它点一下就是勾选，而地图上那些还要建档。
     * 「属于这个社区」在库里没有字段，只能按名字/地址里带不带社区名认 ——
     * 认漏了顶多少列一条（地图那组还会再出一次），认多了也只是多一个候选。
     */
    for (const c of communities.value.filter((c) => c.regionCode === street)) {
      if (!c.name.includes(short) && !(c.address ?? "").includes(short)) continue;
      out.push({
        key: `c${c.communityNo}`,
        name: c.name,
        sub: c.address ?? "",
        hasChild: false,
        picked: has("COMMUNITY", c.communityNo),
        covered: communityCoveredBy(c.regionCode),
        community: c,
      });
    }
    for (const p of villageEstates.value) {
      out.push({
        key: `ve${p.name}${p.address}`,
        name: p.name,
        sub: p.address,
        hasChild: false,
        picked: hitPicked(`ve${p.name}${p.address}`),
        covered: communityCoveredBy(street),
        hit: { key: `ve${p.name}${p.address}`, kind: "POI", name: p.name, sub: p.address,
          latE6: Math.round(p.lat * 1e6), lngE6: Math.round(p.lng * 1e6), address: p.address },
      });
    }
    return out;
  }
  if (!atLeaf.value) {
    return list.value.map((r) => ({
      key: `r${r.regionCode}`,
      name: r.name,
      hasChild: r.hasChild || r.level === "STREET",
      picked: has(r.level, r.regionCode),
      covered: coveredBy(r.regionCode),
      region: r,
    }));
  }
  const out: Row[] = [];
  const street = current.value?.regionCode;
  for (const c of settleRows.value) {
    // 没有 originCode 就下钻不到具体是哪个村/社区（见下），干脆不给 ›，不给一个点了没反应的箭头
    const container = looksLikeContainer(c.name, c.kind) && !!c.originCode;
    /*
     * **村委会（rural=true）永远不给 ›，压根不发起地图查询**——这是服务端存的
     * 判定（sys_region.rural），不是端上猜的。社区/居委会（rural=false）走原来那套：
     * 先给 ›，问过一次确定是空的（knownEmpty）才收回去。两种「不给 ›」的原因不一样：
     * 村委会是结构性的（这一级本来就是终点），社区是数据性的（这次搜到的恰好是空）。
     */
    const drillable = container && !c.rural && !knownEmpty(`C${c.communityNo}`);
    out.push({
      key: `c${c.communityNo}`,
      name: c.name,
      sub: container && !c.rural ? estateNote(`C${c.communityNo}`) : (c.address ?? ""),
      // 已开通的社区底下照样有小区 —— 它开通过，不代表商家要的就是整片（村委会不适用，见上）
      hasChild: drillable,
      picked: has("COMMUNITY", c.communityNo),
      covered: communityCoveredBy(c.regionCode),
      community: c,
      /*
       * **下钻要用 originCode，不是 regionCode**：`c.regionCode` 是这个村/社区挂的
       * 街道/镇码（分组用），不是它自己的码 —— 拿它当下钻目标，「牛杜村」会被当成
       * 「牛杜镇」下钻，列表出来的是同一条街道下所有村委会，而不是这个村底下的自然村。
       * 没有 originCode（地图开通的小区，没有官方村血统）时，本来就不该给 ›。
       */
      region: drillable
        ? { regionCode: c.originCode!, level: "VILLAGE", name: c.name, enabled: true, hasChild: true,
            latE6: c.latE6, lngE6: c.lngE6 } as Region
        : undefined,
    });
  }
  /*
   * 官方名录里的村与地图上的小区**还没开通**，勾一下由系统当场建档再加入 ——
   * 这一步商家看不见（他要的只是「我做这个地方」），所以行样式与上面完全一样，
   * 只在副标题上说明它是哪来的。
   */
  for (const v of villageRows.value) {
    // 同一条规则：村委会（rural）永远不给 ›；居委会/社区先给，问过是空的再收回去
    const drillable = VILLAGE_DRILLABLE && !v.rural && !knownEmpty(v.regionCode);
    out.push({
      key: `v${v.regionCode}`,
      name: cleanVillageName(v.name),
      sub: v.rural ? "" : estateNote(v.regionCode),
      hasChild: drillable,
      picked: hitPicked(`v${v.regionCode}`),
      covered: communityCoveredBy(street),
      region: drillable ? v : undefined,
      hit: { key: `v${v.regionCode}`, kind: "VILLAGE", name: v.name, sub: "",
        regionCode: v.regionCode, streetCode: street, latE6: v.latE6, lngE6: v.lngE6 },
    });
  }
  for (const p of estates.value) {
    out.push({
      key: `p${p.name}${p.address}`,
      name: p.name,
      sub: p.address,
      hasChild: false,
      picked: hitPicked(`p${p.name}${p.address}`),
      covered: communityCoveredBy(street),
      hit: { key: `p${p.name}${p.address}`, kind: "POI", name: p.name, sub: p.address,
        latE6: Math.round(p.lat * 1e6), lngE6: Math.round(p.lng * 1e6), address: p.address },
    });
  }
  return out;
});

/* ================================================================
 * Tab B：搜索（v4）
 *
 * 「按区划」是给「我要整个 XX 区」和「我不知道叫啥，一级级点下去」的；
 * 搜索是给「我知道名字」的。两件事此前挤在同一屏里 —— 搜索框一有字，
 * 层级列表就被顶掉，商家分不清自己在看哪一屏。分成两个 Tab 之后，
 * **行的样式与点法完全一样**，切 Tab 只换内容，不换交互。
 * ================================================================ */
type Tab = "REGION" | "SEARCH";
const tab = ref<Tab>("REGION");

const q = ref("");
const hits = ref<RegionSearchResult | null>(null);
const mapHits = ref<PlaceHit[]>([]);
const searching = ref(false);
let searchTimer: ReturnType<typeof setTimeout> | undefined;

watch(q, (v) => {
  clearTimeout(searchTimer);
  const kw = v.trim();
  if (kw.length < 2) {
    hits.value = null;
    mapHits.value = [];
    searching.value = false;
    return;
  }
  searching.value = true;
  searchTimer = setTimeout(() => void runSearch(kw), 300);
});

async function runSearch(kw: string) {
  try {
    /*
     * **v5：一次调用，服务端统一决定要不要问地图**。
     *
     * 此前端上自己并行发一路给高德（App 走原生 SDK，不经过后端）—— 那条路有两个隐患：
     * 没有圆心时退化成空 city 盲搜，命中率低；原生 SDK 报错又被静默吞掉，「查了没有」
     * 和「请求失败了」在界面上长一个样。现在只调 `mRegionSearch` 一个接口：
     * 服务端自己先查库，库里没有村/小区命中才现问高德（`inputtips`），
     * 城市偏好也是服务端从关键词里切出来的（同一套切法给库内搜索用），
     * App 不用再猜圆心、不用再拆词。
     */
    const center = scopeCenter.value;
    const db = await api.mRegionSearch(kw,
      center ? { latE6: Math.round(center.lat * 1e6), lngE6: Math.round(center.lng * 1e6) } : undefined)
      .catch(() => ({ regions: [], communities: [], villages: [], places: [] }));
    if (q.value.trim() !== kw) return;
    hits.value = db;
    mapHits.value = (db.places ?? [])
      .filter((p) => p.latE6 != null && p.lngE6 != null)
      .map((p) => ({ name: p.name, address: p.address ?? "", city: "",
        lat: p.latE6! / 1e6, lng: p.lngE6! / 1e6 }));
  } finally {
    if (q.value.trim() === kw) searching.value = false;
  }
}

/** 分组标题的顺序 —— 省 › 市 › 区 › 街道，**恒定**，不按相关度重排（见下） */
const LEVEL_ORDER = ["PROVINCE", "CITY", "DISTRICT", "STREET"] as const;

interface Group {
  key: string;
  title: string;
  rows: Row[];
}

/**
 * 搜索结果**按层级从大到小分组**，不按相关度排。
 *
 * 相关度排序会把「深圳市」推到「深圳市某某花园」后面 —— 商家搜「深圳」是想圈整个市，
 * 结果第一屏全是小区，他会以为系统里没有市这一级。层级顺序是他脑子里本来就有的顺序。
 */
const groups = computed<Group[]>(() => {
  const h = hits.value;
  if (!h) return [];
  const out: Group[] = [];
  for (const lv of LEVEL_ORDER) {
    const rs = h.regions.filter((r) => r.level === lv);
    if (!rs.length) continue;
    out.push({
      key: lv,
      title: t(`store.picker.group${lv}`),
      rows: rs.map((r) => ({
        key: `s${r.regionCode}`,
        name: r.name,
        sub: r.path,
        // 街道也能进：它的下一级是村与小区
        hasChild: true,
        picked: has(r.level, r.regionCode),
        covered: coveredBy(r.regionCode),
        region: { regionCode: r.regionCode, level: r.level, name: r.name, enabled: true, hasChild: true, path: r.path } as Region & { path?: string },
        fromSearch: true,
      })),
    });
  }

  /*
   * 村与小区合成一组：**这两者的差别是行政的，不是商家的**。
   * 已开通的排在前面 —— 它点一下就是勾选，而官方村还要建档（虽然商家看不见这一步）。
   */
  const settle: Row[] = [];
  for (const c of h.communities) {
    // 没有 originCode 下钻不到具体是哪个村/社区（同一条判据见「按区划」那一支）
    // 村委会（rural）永远不给 ›，跟「按区划」那一支同一条规则
    const container = looksLikeContainer(c.name, c.kind ?? undefined) && !!c.originCode && !c.rural;
    settle.push({
      key: `sc${c.communityNo}`,
      name: c.name,
      sub: c.path,
      hasChild: container,
      picked: has("COMMUNITY", c.communityNo),
      covered: communityCoveredBy(c.regionCode ?? undefined),
      community: {
        communityNo: c.communityNo, name: c.name, regionCode: c.regionCode ?? undefined, path: c.path,
        originCode: c.originCode, originName: c.originName, rural: c.rural, latE6: c.latE6, lngE6: c.lngE6,
      } as unknown as Community & { path?: string },
      // 下钻用 originCode（它自己的村码），breadcrumb 靠 mRegionPath 从这个码往上走补齐
      region: container
        ? { regionCode: c.originCode!, level: "VILLAGE", name: c.name, enabled: true, hasChild: true,
            latE6: c.latE6, lngE6: c.lngE6 } as Region
        : undefined,
      fromSearch: container,
    });
  }
  for (const v of h.villages ?? []) {
    // 同一个地方已经在上面以「已开通」的样子出现过，就不再出一条「还没开通」的
    if (isOpened(v.name)) continue;
    // 与层级列表里的村行同一条规则：村委会（rural）永远不给 ›
    const drillable = VILLAGE_DRILLABLE && !v.rural;
    settle.push({
      key: `sv${v.regionCode}`,
      name: cleanVillageName(v.name),
      sub: v.path,
      hasChild: drillable,
      picked: hitPicked(`v${v.regionCode}`),
      covered: communityCoveredBy(v.streetCode),
      region: drillable
        ? { regionCode: v.regionCode, level: "VILLAGE", name: v.name, enabled: true,
            hasChild: true, latE6: v.latE6, lngE6: v.lngE6 } as Region
        : undefined,
      fromSearch: drillable,
      hit: { key: `v${v.regionCode}`, kind: "VILLAGE", name: v.name, sub: v.path,
        regionCode: v.regionCode, streetCode: v.streetCode, latE6: v.latE6, lngE6: v.lngE6 },
    });
  }
  if (settle.length) out.push({ key: "SETTLE", title: t("store.picker.groupSettle"), rows: settle });

  /*
   * 地图那一组排最后，且**滤掉库里已经有的**：同一个「福安雅园」在两组里各出现一次，
   * 商家不知道该点哪个 —— 而点地图那条会多走一次建档（服务端会归一回同一条，白等一次网络）。
   */
  const known = new Set([...h.communities.map((c) => c.name), ...(h.villages ?? []).map((v) => v.name)].map(normalizeName));
  const mrows: Row[] = mapHits.value
    .filter((pl) => !known.has(normalizeName(pl.name)) && !isOpened(pl.name))
    .map((pl) => ({
      key: `sm${pl.name}${pl.address}`,
      name: pl.name,
      sub: pl.address,
      hasChild: false,
      picked: hitPicked(`sm${pl.name}${pl.address}`),
      covered: null,
      hit: { key: `sm${pl.name}${pl.address}`, kind: "POI" as HitKind, name: pl.name, sub: pl.address,
        latE6: Math.round(pl.lat * 1e6), lngE6: Math.round(pl.lng * 1e6), address: pl.address },
    }));
  if (mrows.length) out.push({ key: "MAP", title: t("store.picker.groupMap"), rows: mrows });
  return out;
});

/**
 * 点搜索结果里的区划 = **进入它的下一级**（切回「按区划」并停在那一级）。
 * 搜索词留在框里：切回搜索 Tab 结果还在原地，他往往要在两三个候选之间来回看。
 *
 * 面包屑靠 `mRegionPath` 补齐 —— 搜索命中只带一条 `path` 字符串（给人看的），
 * 而面包屑要能点回去，需要每一级的码。
 */
async function openFromSearch(code: string, level: string, self?: Region, container?: Community | null) {
  loading.value = true;
  tab.value = "REGION";
  try {
    const path = await api.mRegionPath(code);
    /*
     * `/biz/regions/path` 只回到街道 —— 村在聚落模型里不算区划一级，服务端把它滤掉了。
     * 而面包屑要停在村上，所以这里把它自己接回去。
     */
    trail.value = level === "VILLAGE" && self ? [...path, self] : path;
  } catch {
    // 拿不到整条路径也别把人卡住：以命中那一级当作栈顶，面包屑短一截，下钻照常
    trail.value = [{ regionCode: code, level, name: q.value.trim(), enabled: true, hasChild: true } as Region];
  } finally {
    loading.value = false;
  }
  const cur = trail.value[trail.value.length - 1];
  if (cur) await enterLevel(cur, container);
  else await loadLevel(undefined);
}

/**
 * 模板只认这一个列表源。两个 Tab 因此共用同一段行渲染 ——
 * 分成两段模板写的话，改一处样式忘了另一处，两个 Tab 就会慢慢长得不一样。
 */
const sections = computed<Group[]>(() =>
  tab.value === "SEARCH" ? groups.value : (rows.value.length ? [{ key: "lvl", title: "", rows: rows.value }] : []));

/** 点右边的 ›：进下一级。搜索命中要先把面包屑补齐（见 openFromSearch） */
function drillRow(r: Row) {
  if (!r.region) return;
  if (r.fromSearch) void openFromSearch(r.region.regionCode, r.region.level, r.region, r.community);
  else void drill(r.region, r.community);
}

/** 点左边的勾：把这一行代表的地方加进来（或取消） */
function pickRow(r: Row) {
  /*
   * 村行**同时**有 region（能下钻）与 hit（能勾选）——先判 hit：
   * 村不是 ServiceArea 的一档（聚落模型里它和小区一样挂在街道下），
   * 走 toggleRegion 会写出一条 level=VILLAGE 的覆盖项，后端一条也展不开。
   */
  if (r.hit) {
    const no = hitAdded.value[r.hit.key];
    if (no && has("COMMUNITY", no)) {
      emit("update:areas", props.areas.filter((a) => !(a.level === "COMMUNITY" && a.refCode === no)));
      return;
    }
    void addHit(r.hit);
    return;
  }
  if (r.region) {
    void toggleRegion(r.region);
    return;
  }
  if (r.community) {
    toggleCommunity(r.community);
    return;
  }
  // 到这里只剩「没有任何来源」的行，什么也不做
}

const adding = ref("");
/**
 * 这一行**刚被加进来时建出来的聚落号**。没有它，官方村与地图地点那几行永远显示未勾 ——
 * 它们不在 `communities` 里按码对得上（地图那条压根没码），于是商家点完看不到勾，
 * 会再点一次：服务端会归一成同一条，但他等的是第二次白跑的网络。
 */
const hitAdded = ref<Record<string, string>>({});

/**
 * 点一行 = 立刻加入。**不弹表单、不跳页、不问街道**。
 *
 * 三种来源各自该做的事系统自己知道：库里有的直接勾；官方村与地图 POI 先开通再勾，
 * 而「开通」这一步商家看不见 —— 他要的只是「我做这个地方」。
 */
async function addHit(h: Hit) {
  if (adding.value) return;
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
      // 存原始官方名（「景滑村委会」），不再存清理过的短名 —— 与国标区划的名字保持一致，
      // 界面上仍然显示清理过的短名（Row.name 那一层已经做了），两件事分开管
      const a = await api.mApplyCommunity({
        name: h.name,
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
    hitAdded.value = { ...hitAdded.value, [h.key]: no };
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
 * 面包屑上那行「整个 XX」。**村与区划走两条路**：区划记码，村要先建档 ——
 * 但对商家是同一件事（「这一整片我都做」），所以界面上是同一行、同一个勾。
 */
const wholeKey = computed(() => (current.value?.level === "VILLAGE" ? `v${current.value.regionCode}` : ""));
const wholePicked = computed(() => {
  const c = current.value;
  if (!c) return false;
  if (c.level !== "VILLAGE") return has(c.level, c.regionCode);
  if (openedContainer.value) return has("COMMUNITY", openedContainer.value.communityNo);
  return hitPicked(wholeKey.value);
});
function toggleWhole() {
  const c = current.value;
  if (!c) return;
  if (c.level !== "VILLAGE") {
    void toggleRegion(c);
    return;
  }
  // 这一层是已开通的社区/村：勾的就是它本身，别照着名字再建一条重复的
  if (openedContainer.value) {
    toggleCommunity(openedContainer.value);
    return;
  }
  const no = hitAdded.value[wholeKey.value];
  if (no && has("COMMUNITY", no)) {
    emit("update:areas", props.areas.filter((a) => !(a.level === "COMMUNITY" && a.refCode === no)));
    return;
  }
  void addHit({
    key: wholeKey.value, kind: "VILLAGE", name: c.name, sub: "",
    regionCode: c.regionCode,
    // 村挂在它上一级的街道下 —— 面包屑里就是前一段
    streetCode: trail.value[trail.value.length - 2]?.regionCode,
    latE6: c.latE6, lngE6: c.lngE6,
  });
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
        已选摘要在**标题栏右侧**：那一行如果放进面板里，会被列表挤出视野，
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

      <!-- 已选清单：误点很容易，必须有个当场能删的地方。开关在标题栏右侧 -->
      <view v-if="chosen.length && chosenOpen" class="chosen">
        <view class="chosen__list">
          <view v-for="a in chosen" :key="a.level + a.refCode" class="chosen__row">
            <text class="chosen__name">{{ a.name }}</text>
            <!-- 待审的要在**已选清单里**看得见：只写在行上的话，勾完就再也看不到了 -->
            <text v-if="areaPending(a)" class="chosen__audit">{{ $t("store.picker.pendingTag") }}</text>
            <text class="chosen__del" @tap="removeArea(a)">{{ $t("common.remove") }}</text>
          </view>
        </view>
      </view>

      <!--
        两个 Tab：**只换内容，不换交互**。行的样式、左右分工、勾选框位置在两边完全一样 ——
        商家不需要在第二个 Tab 里重学一遍怎么点。
      -->
      <view class="tabs">
        <text class="tab" :class="{ 'is-on': tab === 'REGION' }" @tap="tab = 'REGION'">{{ $t("store.picker.tabRegion") }}</text>
        <text class="tab" :class="{ 'is-on': tab === 'SEARCH' }" @tap="tab = 'SEARCH'">{{ $t("store.picker.tabSearch") }}</text>
      </view>

      <template v-if="tab === 'REGION'">
        <!-- 面包屑：这个 Tab 唯一的导航。任一段可点，点了就回到那一级 -->
        <view class="crumb">
          <text class="crumb__i" :class="{ 'is-cur': !trail.length }" @tap="backTo(-1)">{{ $t("store.regionRoot") }}</text>
          <text v-for="(x, i) in trail" :key="x.regionCode" class="crumb__i" :class="{ 'is-cur': i === trail.length - 1 }" @tap="backTo(i)">
            › {{ x.name }}
          </text>
        </view>

        <!--
          「整个 XX」是每一级的第一行，固定在列表上方：
          它让「我就要这一整片」与「我进去挑几个」在同一屏里并列，不用先决定走哪条路。
        -->
        <view v-if="current" class="whole" :class="{ 'is-on': wholePicked }" @tap="toggleWhole">
          <text class="whole__t">{{ $t("store.picker.wholeLevel", { s: current.name }) }}</text>
          <text v-if="wholePicked" class="whole__on">{{ $t("store.picker.picked") }}</text>
        </view>
      </template>

      <view v-else class="filter">
        <sh-icon name="search" :size="16" color="var(--sh-sub)"></sh-icon>
        <input
          v-model="q"
          class="filter__i"
          placeholder-class="sh-ph"
          placeholder-style="color: var(--sh-sub)"
          cursor-color="var(--sh-primary)"
          :placeholder="$t('store.picker.searchPh')"
          confirm-type="search"
        />
        <text v-if="q" class="filter__x" @tap="q = ''">✕</text>
      </view>

      <scroll-view scroll-y class="body">
        <text v-if="tab === 'REGION' && (loading || villageEstatesLoading)" class="hint">{{ $t("common.loading") }}</text>
        <text v-else-if="tab === 'SEARCH' && q.trim().length < 2" class="hint">{{ $t("store.picker.searchTip") }}</text>
        <text v-else-if="tab === 'SEARCH' && searching" class="hint">{{ $t("common.loading") }}</text>
        <template v-else>
          <!--
            **每一级、每一组都是同一种行**：左边勾选＝把这一整片加进来，右边 › ＝进下一级。
            最后一级（小区/村）没有下级，所以只有勾选 —— 除此之外与上面几级一模一样。
          -->
          <template v-for="g in sections" :key="g.key">
            <text v-if="g.title" class="group">{{ g.title }}</text>
            <view v-for="r in g.rows" :key="r.key" class="row" :class="{ 'is-covered': coverNote(r) }">
              <view class="row__main" @tap="r.hasChild ? drillRow(r) : pickRow(r)">
                <text class="row__name">{{ r.name }}</text>
                <text v-if="coverNote(r)" class="row__sub">
                  {{ $t("store.picker.coveredTag", { s: shortName(coverNote(r)?.name) }) }}
                </text>
                <text v-else-if="r.sub" class="row__sub">{{ r.sub }}</text>
              </view>
              <view class="row__check" :class="{ 'is-on': r.picked, 'is-off': !!coverNote(r) }" @tap.stop="pickRow(r)">
                <text v-if="r.picked" class="row__tick">✓</text>
                <text v-else-if="adding === r.key" class="row__tick">…</text>
              </view>
              <!--
                竖线把两个点击区分开。**这不是装饰**：左边是「把整片加进来」（勾一个市影响几千个小区），
                右边只是「换一屏」—— 后果差着量级的两个动作挨在一起，手一滑就是一条待审记录。
              -->
              <template v-if="r.hasChild">
                <view class="row__sep"></view>
                <sh-icon name="chevronRight" :size="18" color="var(--sh-sub)" @tap.stop="drillRow(r)"></sh-icon>
              </template>
            </view>
          </template>

          <text v-if="!sections.length" class="hint">
            {{ tab === "SEARCH" ? $t("store.picker.searchEmpty")
              : atVillage ? $t("store.picker.villageEmpty") : $t("store.picker.levelEmpty") }}
          </text>

        </template>

        <!--
          地图入口在**两个 Tab 的列表末尾都有**，位置恒定 —— 连「搜索框还空着」「正在搜」
          这两个瞬间也在：系统里没有的地方只有这一条路，藏起来商家就会以为「这个小区做不了」。
          点回来直接建档并勾上，没有提报、没有等待（v4）。
        -->
        <view class="maprow" @tap="pickOnMapAndAdd">
          <text class="maprow__t">{{ picking ? $t("common.loading") : $t("store.picker.mapEntry") }}</text>
          <sh-icon name="chevronRight" :size="18" color="var(--sh-primary-text)"></sh-icon>
        </view>
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
.crumb {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
  margin: 20rpx 32rpx 0;
  font-size: 24rpx;
  color: var(--sh-sub);
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
  background: var(--sh-primary-tint);
}
.whole__t {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
}
.whole__on {
  padding: 4rpx 16rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 24rpx;
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
  padding: 20rpx 32rpx;
  border-bottom: 2rpx solid var(--sh-line);
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__name {
  display: block;
  font-size: 28rpx;
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
  padding: 20rpx 32rpx 8rpx;
  font-size: 22rpx;
  letter-spacing: 0.06em;
  color: var(--sh-sub);
  background: var(--sh-bg);
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
  margin: 20rpx 24rpx 4rpx;
  padding: 0 24rpx;
  height: 72rpx;
  border-radius: 24rpx;
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

/* 已选清单：标题栏右侧「展开」出来的那个浮层，误点很容易，要有个当场能删的地方 */
.chosen {
  margin: 0 24rpx 16rpx;
  padding: 8rpx 0;
  border-radius: 24rpx;
  background: var(--sh-faint);
}
.chosen__row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 24rpx;
  font-size: 26rpx;
  color: var(--sh-ink);
}
.chosen__row + .chosen__row {
  border-top: 2rpx solid var(--sh-line);
}
.chosen__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chosen__del {
  flex-shrink: 0;
  font-size: 24rpx;
  color: var(--sh-sub);
}

/* 已选清单里的待审标：与行内那句「选中后需运营审核」是同一件事的两个时刻 */
.chosen__audit {
  flex-shrink: 0;
  padding: 2rpx 12rpx;
  border-radius: 12rpx;
  font-size: 22rpx;
  background: var(--sh-warning-tint);
  color: var(--sh-warning);
}

/* Tab：两段式，唯一的模式切换。下划线只压在文字下面，不铺满整段 */
.tabs {
  display: flex;
  gap: 44rpx;
  padding: 4rpx 32rpx 0;
  border-bottom: 2rpx solid var(--sh-line);
}
.tab {
  position: relative;
  padding: 16rpx 4rpx 20rpx;
  font-size: 30rpx;
  color: var(--sh-sub);
}
.tab.is-on {
  color: var(--sh-ink);
  font-weight: 600;
}
.tab.is-on::after {
  content: "";
  position: absolute;
  left: 50%;
  bottom: -2rpx;
  width: 44rpx;
  height: 4rpx;
  margin-left: -22rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
}

/* 地图入口：两个 Tab 的列表末尾都有，位置恒定 */
.maprow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 32rpx;
}
.maprow__t {
  font-size: 28rpx;
  color: var(--sh-primary-text);
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
