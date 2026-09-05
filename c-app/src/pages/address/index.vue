<script setup lang="ts">
// 地址簿：列表 + 新增/编辑（同页内弹层，不再多开一页 —— 表单只有 5 个字段）。
// `picking=1` 时从结算页进入，选中即回填并返回。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useLocationStore } from "@/stores/location";
import type { Address } from "@shared/types";
import { canChooseLocation, chooseLocation, chooseWxAddress, getLocationDetailed } from "@shared/ports/location";
import { readClipboard } from "@shared/ports/clipboard";
import { parsePastedAddress } from "@shared/utils/address-paste";
import { confirm } from "@ai-shop/ui/prompt";
import { isPhone, notBlank } from "@shared/utils/validate";
import { pickedAddress, pickedPlace } from "@/shared/address-pick";
import type { PlacePick } from "@/shared/address-pick";
import { canSearchPlaces } from "@shared/ports/geo-search";
import { ADDRESS_RULES, ROUTES } from "@shared/utils/constants";
import { isCompleteRegion, joinRegion, splitRegion } from "@shared/utils/region";

const { t } = useI18n();

const list = ref<Address[]>([]);
const picking = ref(false);
const location = useLocationStore();
const editing = ref(false);
/** 编辑中的草稿。addressId 为空 = 新增 */
const draft = ref<Omit<Address, "addressId"> & { addressId?: string }>({
  name: "",
  phone: "",
  region: "",
  province: "",
  city: "",
  district: "",
  detail: "",
  houseNo: "",
  isDefault: false,
  tag: "",
  latE6: null,
  lngE6: null,
});

/**
 * 地图选点。一次拿到省市区、门牌与**坐标** —— 手填出来的地址只是一串字，
 * 商家的自送半径、骑手导航、按位置找店全都用不上它。
 * 不支持选点的端（H5 没配 JS key）静默不显示这个入口，手填照旧。
 */
/**
 * 一键导入微信里存的收货地址。**只填字，不带坐标** ——
 * 需要坐标（配送半径、骑手导航）时仍要点「地图选点」，两者是配合不是替代。
 *
 * <p>不覆盖用户已经填了的格子：他可能先手填了一半才想起来有这个按钮，
 * 一键把他刚敲的字冲掉是最让人恼火的那种「贴心」。
 */
async function fillFromWx() {
  const a = await chooseWxAddress();
  if (!a) return; // 取消 / 不支持：什么都不做，不弹提示
  const put = (k: "name" | "phone" | "detail", v: string) => {
    if (v && !draft.value[k].trim()) draft.value[k] = v;
  };
  put("name", a.name);
  put("phone", a.phone);
  put("detail", a.detail);
  if (a.province && !draft.value.province) {
    draft.value.province = a.province;
    draft.value.city = a.city;
    draft.value.district = a.district;
    draft.value.region = joinRegion({ province: a.province, city: a.city, district: a.district });
  }
}

const picked = computed(() => draft.value.latE6 != null && draft.value.lngE6 != null);
async function pickOnMap() {
  const init = picked.value
    ? { lat: draft.value.latE6! / 1e6, lng: draft.value.lngE6! / 1e6 }
    : undefined;
  const r = await chooseLocation(init);
  if (!r.ok) {
    if (r.reason === "unsupported") uni.showToast({ title: String(t("address.mapUnsupported")), icon: "none" });
    return;
  }
  const p = r.picked;
  draft.value.latE6 = Math.round(p.lat * 1e6);
  draft.value.lngE6 = Math.round(p.lng * 1e6);
  /*
   * 地图给的 address 是「省市区 + 路名门牌」一整串。
   * **拆开存**：不拆的话 province/city/district 三列还是 null，
   * 而地图选点本来是这条链路上信息最全的一次输入 —— 在这里丢掉最可惜。
   */
  if (p.address) {
    const parts = splitRegion(p.address.slice(0, 96));
    draft.value.province = parts.province;
    draft.value.city = parts.city;
    draft.value.district = parts.district;
    // 拆不出省市区的（只有门牌的写法）保持原样，别把一整串塞进 region 又清空三列
    draft.value.region = isCompleteRegion(parts) ? joinRegion(parts) : p.address.slice(0, 96);
    if (!draft.value.detail.trim() && parts.rest.trim()) draft.value.detail = parts.rest.trim().slice(0, 60);
  }
  if (!draft.value.detail.trim() && p.name) draft.value.detail = p.name.slice(0, 60);
}

/**
 * 粘贴一段文字，认出姓名 / 手机 / 省市区 / 地址主体 / 门牌。
 *
 * <p><b>只填空着的格子，绝不覆盖他已经填了的字。</b>与旁边「微信地址」那个
 * 入口同一条规矩（见 `fillFromWx`）：他可能先手填了一半才想起来有这个按钮，
 * 一键把刚敲的字冲掉是最让人恼火的那种「贴心」。
 *
 * <p><b>它不给坐标</b>，所以不是选点页的替代 —— 粘完仍然要点一次地图选点，
 * 否则商家的自送半径判不了。这件事由保存按钮上方那句提示负责说。
 */
async function pasteAndFill() {
  const text = await readClipboard();
  if (!text.trim()) {
    uni.showToast({ title: String(t("address.pasteEmpty")), icon: "none" });
    return;
  }
  const r = parsePastedAddress(text);
  if (!r) {
    uni.showToast({ title: String(t("address.pasteFailed")), icon: "none" });
    return;
  }
  const put = (k: "name" | "phone" | "detail" | "houseNo" | "region", v: string) => {
    if (v && !String(draft.value[k] ?? "").trim()) draft.value[k] = v;
  };
  put("name", r.name);
  put("phone", r.phone);
  put("detail", r.detail);
  put("houseNo", r.houseNo);
  if (r.region && !draft.value.region.trim()) {
    draft.value.region = r.region;
    draft.value.province = r.province;
    draft.value.city = r.city;
    draft.value.district = r.district;
  }
  uni.showToast({ title: String(t("address.pasteDone")), icon: "none" });
}

const pickingRegion = ref(false);

/** 选择器回来：三级都是**名字**，region 由它们拼出来，不再各写各的 */
function onRegionPick(v: { province: string; city: string; district: string }) {
  draft.value.province = v.province;
  draft.value.city = v.city;
  draft.value.district = v.district;
  draft.value.region = joinRegion(v);
  pickingRegion.value = false;
}

/**
 * 手填那条路仍然留着（存量地址、区划表里没有的写法），但**填完要拆一次** ——
 * 否则手填的地址三列依旧是空的，跟改造前没区别。
 */
function onRegionInput() {
  const parts = splitRegion(draft.value.region);
  draft.value.province = parts.province;
  draft.value.city = parts.city;
  draft.value.district = parts.district;
}

/**
 * 手填的一串**拆不出省市区**时给一句提示。
 *
 * 刻意**不拦保存**：拆不动是常态（存量地址、只写小区门牌的写法），
 * 拦了等于让一部分人存不了地址。但也不能一声不吭 ——
 * 不吭声的话那三列静默为空，而按区派单会跳过这个人，谁都不知道为什么。
 */
const regionUnsplit = computed(
  () => notBlank(draft.value.region) && !isCompleteRegion(draft.value),
);

const valid = computed(
  () =>
    notBlank(draft.value.name) &&
    // 此前是 `/^\d{11}$/` —— 只查长度，`00000000000` 一路存进地址簿
    isPhone(draft.value.phone) &&
    notBlank(draft.value.region) &&
    notBlank(draft.value.detail) &&
    /*
     * 门牌号**端上必填**。后端刻意没有 @NotBlank：还没更新的老版本 App 压根不发这个字段，
     * 后端要着的话它连「改个手机号」都保存不了。所以这条闸只在这里。
     */
    notBlank(draft.value.houseNo ?? ""),
);

async function load() {
  list.value = await api.addressList();
}

/**
 * 打开空白表单。`place` 有值时用它预填地址主体与**坐标** ——
 * 那条坐标正是走一趟选点页的全部收获。
 */
function openNew(place?: PlacePick) {
  draft.value = {
    name: "", phone: "",
    region: place?.region ?? "",
    province: place?.province ?? "",
    city: place?.city ?? "",
    district: place?.district ?? "",
    detail: place?.name ?? "",
    houseNo: "",
    isDefault: !list.value.length, tag: "",
    latE6: place?.latE6 ?? null,
    lngE6: place?.lngE6 ?? null,
  };
  editing.value = true;
}

/**
 * 「新增地址」**先去选点页**，让地址从一开始就带坐标。
 *
 * <p><b>但这个端给不了任何一条选点路时直接开表单</b>（H5：没有原生搜索、
 * 也没配地图 JS key）。否则那一页对他只剩一行「手动填写」，
 * 白挡一次点击，比改造前更差。
 */
/**
 * 这个端有没有任何一条选点路。**同一个判断供两处用**：
 * 「新增」要不要进选点页，以及表单里的地址主体要不要设成只读。
 * 两处分开写的话，H5 上会出现「进不了选点页、地址主体却锁着」——他就永远存不了地址。
 */
const canPick = computed(() => canSearchPlaces() || canChooseLocation());

/**
 * 预设标签。**存的是当前语言下的显示文案，不是码。**
 *
 * <p>本来该存 `HOME/WORK/SCHOOL` 再按词条渲染（这仓对枚举就是这个规矩）。
 * 没这么做的理由很具体：顶栏那个短名走的是 `location` store 的 `label` getter，
 * 而 `packages/shared/src/utils/locale.ts` 明确禁止 shared/store 反向依赖各端的 i18n ——
 * 存码就得把翻译推进 store，或者让 getter 返回一个「这段要翻、那段别翻」的复合值。
 * 为一个**纯装饰、无任何逻辑匹配**的字段（全仓只有三处在显示它）付这个代价不值。
 *
 * <p>代价说清楚：切了界面语言之后，已存的标签仍是当时那个语言的字。
 * 自定义标签本来就是这样（那是用户自己写的字），预设的三个会略显别扭。
 */
const TAG_PRESETS = ["tagHome", "tagWork", "tagSchool"] as const;

/**
 * 当前定位**匹配到的那条收货地址**。null = 一条都没匹配到。
 *
 * <p><b>定位只做这一件事</b>（PRD §6.1.0）：它不直接选聚落 ——
 * 位置永远是一条地址，聚落匹配是那条地址的下游。
 * 把定位做成能直接选聚落的第二条路，等于让用户理解两套东西。
 */
const locatedMatch = ref<string>("");
/** 拿到了定位但一条地址都没匹配上 —— 此时以「当前位置」为准，而不是回落到无位置 */
const locatedAt = ref<{ lat: number; lng: number } | null>(null);

async function detectHere() {
  const r = await getLocationDetailed();
  // 模糊坐标（区级，误差 5 公里）匹配收货地址同样是噪音，一律不用
  if (!r.ok || r.fuzzy) return;
  locatedAt.value = { lat: r.coords.lat, lng: r.coords.lng };
  locatedMatch.value = location.suggestNearest(r.coords)?.addressId ?? "";
}

/**
 * 以当前位置为准。**不入地址簿** —— 地址簿上限 20 条，
 * 每次「用一下现在这儿」都存一条会很快塞满；下单时再问要不要存。
 */
async function useCurrentLocation() {
  const at = locatedAt.value;
  if (!at) return;
  await location.useTransient(at);
  uni.showToast({ title: String(t("address.nowAtCurrent")), icon: "none" });
}

/** 到上限了。真正的闸在后端（老版本 App 不知道有这回事），这里只是提前说一声 */
const atLimit = computed(() => list.value.length >= ADDRESS_RULES.maxCount);

function addNew() {
  if (atLimit.value) {
    uni.showToast({ title: String(t("address.limitReached", { n: ADDRESS_RULES.maxCount })), icon: "none" });
    return;
  }
  if (!canPick.value) {
    openNew();
    return;
  }
  uni.navigateTo({ url: ROUTES.addressPick });
}

/** 从表单里回选点页重选地址主体。草稿留着 —— 姓名手机他已经填了 */
function repick() {
  editing.value = false;
  uni.navigateTo({ url: ROUTES.addressPick });
}

function openEdit(a: Address) {
  // houseNo 存量为 null，直接绑到 input 上会显示 "null"
  draft.value = { ...a, houseNo: a.houseNo ?? "" };
  editing.value = true;
}

async function save() {
  if (!valid.value) {
    uni.showToast({ title: String(t("address.invalid")), icon: "none" });
    return;
  }
  list.value = await api.saveAddress({ ...draft.value });
  editing.value = false;
}

async function remove(a: Address) {
  const ok = await confirm({ title: String(t("address.removeTitle")), hint: `${a.region} ${a.detail}` });
  if (!ok) return;
  list.value = await api.removeAddress(a.addressId);
}

/**
 * 设为当前位置 —— **和「设为默认」是两个动作，界面上也要是两个按钮。**
 *
 * <p>「当前位置」决定这一次逛看到哪些商家与商品；「默认」决定下单时预填哪个收货人。
 * 给父母下单的人会切到父母家看货，但默认收货人仍是自己 ——
 * 把两者做成一个按钮，他就没法表达这件事了。
 */
async function useHere(a: Address) {
  const { rebound } = await location.switchTo(a.addressId);
  const name = a.tag || a.detail;
  /*
   * **没换成也要说一句。**
   *
   * 没坐标的地址（微信导入、粘贴识别、存量手填）推不出聚落，归属**保持不变** ——
   * 那是对的，清掉的话他会发现「换了个地址，商品全没了」。
   * 但一声不吭同样糟：顶栏变了、商品没变，他无从判断是坏了还是本该如此。
   * 说清楚之后，他至少知道下一步是去补一个定位点。
   */
  uni.showToast({
    title: String(rebound
      ? t("address.nowHere", { name })
      : t("address.nowHereNoCoord", { name })),
    icon: "none",
    duration: rebound ? 1500 : 3000,
  });
  /*
   * **切完就回上一页**（首页 / 我的 —— 他是从哪儿进来的就回哪儿）。
   *
   * 切换是手段不是目的：他要的是「看那一片的货」，而这一页**看不见货** ——
   * 留在这儿他只能看到一个 toast，然后自己按返回。中间那一步没有任何信息，
   * 纯粹是让他多点一下。首页的 onShow 里有 load()，回去就是新的那一片。
   *
   * 等一下再回：toast 弹出来那一刻页面就跳走的话，他会以为自己点空了。
   * 没有定位点那条要说的话更长（3 秒那条），所以按 toast 的时长走。
   */
  setTimeout(() => uni.navigateBack(), rebound ? 600 : 1200);
}

async function setDefault(a: Address) {
  list.value = await api.setDefaultAddress(a.addressId);
}

/**
 * 从结算页进来时，点一条即选中并返回。
 *
 * <p><b>不动默认地址。</b>此前这里是 `await api.setDefaultAddress(...)` ——
 * 借「改默认」来传「这一单选谁」。它能工作，所以一直没人看出问题：
 * 副作用是**长期偏好被一单改写**，给父母寄一次，从此每单都预填父母家。
 * 现在改为交回一个 id，由结算页自己决定这一单用哪条（`shared/address-pick`）。
 */
function pick(a: Address) {
  /*
   * **不是选址模式时，点一整张卡就是「切到这儿」**（美团的口径）。
   *
   * 此前这里直接 return —— 于是从「我的」进来点地址什么也不会发生，
   * 切换只藏在那个「设为当前位置」的小字上。而这一页的注释一直写着
   * 「点一下就切，不弹窗不追问」：**说的和做的不是一回事**，
   * 而不一致的那一半是用户会先撞上的那一半。
   *
   * 已经是当前位置的那条不用再切一次（切了也只是重放一遍同样的 toast）。
   */
  if (!picking.value) {
    if (location.active?.addressId !== a.addressId) void useHere(a);
    return;
  }
  pickedAddress.offer(a.addressId);
  uni.navigateBack();
}

onLoad((q) => {
  picking.value = q?.picking === "1";
  load();
  void location.load().then(() => detectHere());
});

/**
 * 从选点页回来：**选中的地点要立刻落进一张打开的表单**，别让他自己再点一次「新增」。
 *
 * <p>三种回法要分开：交回地点 → 开预填的表单；交回 manual → 开空表单；
 * 什么都没交回（点了系统返回）→ **什么都不做**。
 * 把第三种也当成「开表单」的话，用户每次退出选点页都会被塞一张表单。
 */
onShow(() => {
  const p = pickedPlace.take();
  if (!p) return;
  openNew(p.kind === "place" ? p : undefined);
});
</script>

<template>
  <sh-scaffold title-key="address.title">
    <!--
      **一条都没匹配到时，以当前位置为准。**（PRD §6.1.0）
      不是死路：他照样能逛、能下单；要不要存成地址，下单时再问。
      有匹配的话不显示这一条 —— 那条地址就在下面，标着「你在这儿」。
    -->
    <view v-if="locatedAt && !locatedMatch" class="sh-card here" @tap="useCurrentLocation">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ $t("address.useCurrentLocation") }}</text>
        <text class="txt-caption txt-primary">{{ $t("address.useIt") }}</text>
      </view>
      <text class="txt-caption here__sub">{{ $t("address.noMatchHint") }}</text>
    </view>

    <view v-for="a in list" :key="a.addressId" class="sh-card card" @tap="pick(a)">
      <view class="card__head sh-wrap">
        <text class="txt-strong">{{ a.name }}</text>
        <text class="txt-caption sh-num">{{ a.phone }}</text>
        <text v-if="a.tag" class="txt-caption sh-chip tiny">{{ a.tag }}</text>
        <text v-if="a.isDefault" class="txt-caption sh-chip sh-chip--primary tiny">
          {{ $t("address.default") }}
        </text>
        <!-- 定位匹配到的那条：标出来即可，**点一下就切**，不弹窗不追问 -->
        <text v-if="a.addressId === locatedMatch" class="txt-caption sh-chip sh-chip--primary tiny">
          {{ $t("address.youAreHere") }}
        </text>
      </view>
      <text class="txt-caption card__addr">{{ a.region }} {{ a.detail }} {{ a.houseNo }}</text>

      <view class="card__ops">
        <!--
          **只保留「当前位置」这个状态标，不再单给一个「设为当前位置」按钮** ——
          整张卡点一下就是切换（见 pick），再摆一个按钮就是同一件事的第二个入口，
          而两个入口里总有一个会先坏掉、且没人发现。
        -->
        <text v-if="location.active?.addressId === a.addressId"
              class="txt-caption txt-strong op is-active">{{ $t("address.here") }}</text>
        <text v-if="!a.isDefault" class="txt-caption op txt-primary" @tap.stop="setDefault(a)">
          {{ $t("address.setDefault") }}
        </text>
        <text class="txt-caption op txt-primary" @tap.stop="openEdit(a)">{{ $t("address.edit") }}</text>
        <text class="txt-caption op is-danger" @tap.stop="remove(a)">{{ $t("address.remove") }}</text>
      </view>
    </view>

    <sh-empty bare v-if="!list.length" :text='$t("address.empty")'></sh-empty>

    <sh-actionbar :pad="160">
      <view class="sh-btn" :class="{ 'is-disabled': atLimit }" @tap="addNew">
        {{ atLimit ? $t("address.limitReached", { n: ADDRESS_RULES.maxCount }) : $t("address.add") }}
      </view>
    </sh-actionbar>

    <!-- 编辑弹层 -->
    <sh-sheet
      :visible="editing"
      :title="String(draft.addressId ? $t('address.edit') : $t('address.add'))"
      @close="editing = false"
    >
        <!--
          **放在最上面，不放省市区那一行。** 它填的是整张表，
          而那一行已经有「请选择 / 地图选点 / 微信地址」三个按钮 ——
          小程序上再挤一个，输入框只剩指甲盖那么宽。
        -->
        <view class="pasterow sh-row sh-row--between" @tap="pasteAndFill">
          <text class="txt-caption pasterow__text">{{ $t("address.pasteHint") }}</text>
          <text class="txt-caption txt-primary">{{ $t("address.paste") }}</text>
        </view>
        <input maxlength="64" v-model="draft.name" class="field__input" :placeholder="$t('address.name')" />
        <input
          v-model="draft.phone"
          class="field__input"
          type="number"
          maxlength="11"
          :placeholder="$t('address.phone')"
        />
        <view class="regionrow sh-row">
          <input
            maxlength="96"
            v-model="draft.region"
            class="field__input sh-fill"
            :placeholder="$t('address.region')"
            @blur="onRegionInput"
          />
          <text class="txt-caption regionrow__pick" @tap="pickingRegion = true">{{ $t("address.regionSelect") }}</text>
          <text class="txt-caption regionrow__pick" :class="{ 'is-ok': picked }" @tap="pickOnMap">
            {{ picked ? $t("address.repick") : $t("address.pick") }}
          </text>
          <!-- #ifdef MP-WEIXIN -->
          <text class="txt-caption regionrow__pick" @tap="fillFromWx">{{ $t("address.fromWx") }}</text>
          <!-- #endif -->
        </view>
        <text v-if="regionUnsplit" class="sh-hint">{{ $t("address.regionIncomplete") }}</text>
        <!--
          地址主体：能选点的端上**只读**，改要回选点页。
          它是跟坐标一起来的，在这里随手改几个字，坐标不会跟着动 ——
          于是「文字写着 A、坐标指着 B」，而页面上完全看不出来。
          没有任何选点路的端（H5）保持可输入，否则他连存量地址都改不了。
        -->
        <view class="regionrow sh-row">
          <input
            maxlength="255"
            v-model="draft.detail"
            class="field__input sh-fill"
            :disabled="canPick"
            :placeholder="$t('address.detail')"
          />
          <text v-if="canPick" class="txt-caption regionrow__pick" @tap="repick">
            {{ $t("address.repickPlace") }}
          </text>
        </view>
        <input
          maxlength="40"
          v-model="draft.houseNo"
          class="field__input"
          :placeholder="$t('address.houseNo')"
        />
        <!--
          标签：预设三个点一下就填好，旁边仍留一个输入框。
          **不做成「预设/自定义」两种模式** —— 输入框始终是唯一真源，
          chip 只是快捷方式，于是没有「我现在处在哪种模式」这个问题。
        -->
        <view class="tagrow sh-row">
          <text
            v-for="k in TAG_PRESETS"
            :key="k"
            class="txt-caption tagrow__chip"
            :class="{ 'is-on': draft.tag === $t(`address.${k}`) }"
            @tap="draft.tag = String($t(`address.${k}`))"
          >{{ $t(`address.${k}`) }}</text>
        </view>
        <!-- 16 → 8：顶栏的短名直接显示它，16 个字会把那一行撑爆 -->
        <input maxlength="8" v-model="draft.tag" class="field__input" :placeholder="$t('address.tagPh')" />

        <view class="switchrow sh-row sh-row--between" @tap="draft.isDefault = !draft.isDefault">
          <text class="txt-sub switchrow__label txt-ink">{{ $t("address.asDefault") }}</text>
          <sh-switch :model-value="draft.isDefault"></sh-switch>
        </view>

        <!--
          **没有坐标就说一句。** 手填、微信导入、粘贴识别三条路都只给字不给坐标，
          而没坐标的地址上，商家的自送半径判不了（后端那条闸明写着「没坐标就放行」）、
          骑手导航也打不开 —— 三件事在界面上都看不出区别，所以必须在这里说。

          **刻意不拦保存**：拦了等于让一部分人存不了地址（存量地址、POI 搜不到的地方
          本来就没有坐标），与旁边 regionUnsplit 那句是同一种口径：提示，不阻断。
        -->
        <view v-if="!picked" class="nocoord sh-row sh-row--between">
          <text class="txt-caption nocoord__text">{{ $t("address.noCoordHint") }}</text>
          <text v-if="canPick" class="txt-caption txt-primary" @tap="pickOnMap">
            {{ $t("address.pick") }}
          </text>
        </view>

        <view class="sh-btn sheet__save" :class="{ 'is-disabled': !valid }" @tap="save">
          {{ $t("common.confirm") }}
        </view>
    </sh-sheet>

    <biz-region-picker
      :visible="pickingRegion"
      :current="draft.region"
      @close="pickingRegion = false"
      @pick="onRegionPick"
    ></biz-region-picker>
  </sh-scaffold>
</template>

<style scoped>
/* 字重交给字阶类 txt-strong（见 规范-字体），这里只管颜色 */
.is-active {
  color: var(--sh-primary);
}

.regionrow {
  gap: 12rpx;
}

.regionrow__pick {
  flex-shrink: 0;
  padding: 12rpx 20rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
.regionrow__pick.is-ok {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
}
.card {
  margin-bottom: 20rpx;
}
.card__head {
  align-items: center;
}

.tiny {
  padding: 4rpx 14rpx;
}
.card__addr {
  display: block;
  margin-top: 16rpx;
}
.card__ops {
  display: flex;
  justify-content: flex-end;
  gap: 32rpx;
  margin-top: 20rpx;
}
/* 共用的 `.field__input`（88rpx 高 / md 圆角 / faint 底 / 30rpx）已经是这个形状 ——
   此前这里把它重写了一遍，而且字号写成 26rpx，比 base.css 的 30rpx 小两档。
   这里只留这一页特有的：字段之间的纵向间距。 */
.field__input {
  margin-top: 16rpx;
}
.here {
  margin-bottom: 20rpx;
}
.here__sub {
  display: block;
  margin-top: 12rpx;
}
.tagrow {
  gap: 12rpx;
  margin-top: 16rpx;
}
.tagrow__chip {
  padding: 12rpx 24rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
.tagrow__chip.is-on {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
}
.pasterow {
  padding: 16rpx 20rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  gap: 16rpx;
}
.pasterow__text {
  flex: 1;
}
.nocoord {
  margin-top: 24rpx;
  padding: 16rpx 20rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  gap: 16rpx;
}
.nocoord__text {
  flex: 1;
}
.switchrow {
  margin-top: 28rpx;
}
.sheet__save {
  margin-top: 36rpx;
}
</style>
