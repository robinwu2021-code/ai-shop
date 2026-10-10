<script setup lang="ts">
/**
 * 新建 / 编辑一条收货地址 —— **这张表只有一份实现**。
 *
 * <p>此前它长在收货地址页的弹层里，而下单页要用时只能靠 `?new=1` 去把那个弹层
 * 打开。于是「新建地址」有两个入口形态，而弹层在小程序上高度受限：
 * 7 个字段 + 3 条提示塞进去看不到头，用户不知道「还要填多少」。
 *
 * <p>抽成组件之后，装它的是整页（`pages/address-edit`），
 * 谁要用就跳那一页 —— 入口只有一种形态。
 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { Address } from "@shared/types";
import { canChooseLocation, canChooseWxAddress, chooseLocation, chooseWxAddress } from "@shared/ports/location";
import { isPhone, notBlank } from "@shared/utils/validate";
import { canSearchPlaces } from "@shared/ports/geo-search";
import { ROUTES } from "@shared/utils/constants";
import { isCompleteRegion, joinRegion, splitRegion } from "@shared/utils/region";
import type { PlacePick } from "@/shared/address-pick";
import { useUserStore } from "@/stores/user";
import PhoneGate from "@/components/phone-gate.vue";

const props = defineProps<{
  /** 编辑模式：要改的那一条 */
  address?: Address | null;
  /** 新建模式：从选点页交回来的地点，用它预填地址主体与**坐标** */
  place?: PlacePick | null;
  /** 这是他的第一条地址 —— 那就默认设为默认地址 */
  first?: boolean;
}>();

const emit = defineEmits<{
  (e: "saved", list: Address[]): void;
}>();

const { t } = useI18n();
const user = useUserStore();

/**
 * **新增地址时手机号栏默认填他自己的号**（资料接口给本人的是完整号，不脱敏）。
 *
 * 此前每条新地址的手机号都是空的：刚在下单页验证过的号，到这里要再输一遍。
 * 只是默认值 —— 寄给家人时他改掉就是。编辑存量地址不动它，那条上的号是他当时填的。
 */
function accountPhone(): string {
  const p = user.user?.phone ?? "";
  return isPhone(p) ? p : "";
}

/** 编辑中的草稿。addressId 为空 = 新增 */
const draft = ref<Omit<Address, "addressId"> & { addressId?: string }>(
  props.address
    // houseNo 存量为 null，直接绑到 input 上会显示 "null"
    ? { ...props.address, houseNo: props.address.houseNo ?? "" }
    : {
      name: "", phone: accountPhone(),
      region: props.place?.region ?? "",
      province: props.place?.province ?? "",
      city: props.place?.city ?? "",
      district: props.place?.district ?? "",
      detail: props.place?.name ?? "",
      houseNo: "",
      isDefault: !!props.first, tag: "",
      countryCode: "CN",
      postalCode: "",
      phoneCc: "86",
      latE6: props.place?.latE6 ?? null,
      lngE6: props.place?.lngE6 ?? null,
    },
);

const canWx = canChooseWxAddress();

async function fillFromWx() {
  const a = await chooseWxAddress();
  if (!a) return; // 取消 / 不支持：什么都不做，不弹提示
  const put = (k: "name" | "phone" | "detail", v: string) => {
    /*
     * 手机号栏里如果还是**我们预填的本人号**，微信地址簿里的号要能盖掉它 ——
     * 他导入的多半是家人那一条，名字换成了家人、电话却还是自己的，送货打错人。
     */
    // String()：手机号栏是 type="number"，H5 上 v-model 会把它转成数字，`.trim` 当场抛错
    const cur = String(draft.value[k] ?? "").trim();
    const untouched = k === "phone" && cur === accountPhone();
    if (v && (untouched || !cur)) draft.value[k] = v;
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

/**
 * 还没绑手机号：手机号栏下面给一个绑定入口，绑完直接回填到这一栏。
 *
 * 不做成硬门槛（不绑不让存地址）：寄给家人的人并不需要先交出自己的号。
 * 但这里绑了，下单那一步就不会再问 —— 否则地址里填一遍、下单再验一遍。
 * 他在手机号栏里已经输了号码的话，带进弹层，只剩验证码要填。
 */
const phoneGate = ref(false);
function onPhoneBound() {
  phoneGate.value = false;
  const p = accountPhone();
  if (p && !isPhone(String(draft.value.phone ?? ""))) draft.value.phone = p;
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
  applyPicked(r.picked);
}

/**
 * 把地图选到的那个点落进草稿。**两处共用**（「重选」与「没有坐标」那条提示里的
 * 「地图选点」）—— 各写一份的话省市区的拆法迟早不一样，而那种不一致在界面上
 * 看不出来，只会让「按区派单」偶尔落错。
 *
 * @param replace 「重选」传 true：他就是来换地点的，地址主体要跟着换。
 *                默认 false —— 那时不覆盖他已经敲的字（先手填了一半才想起来
 *                有这个按钮，一键把刚敲的冲掉是最让人恼火的那种「贴心」）
 */
function applyPicked(p: { name?: string; address?: string; lat: number; lng: number },
                     replace = false) {
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
    if ((replace || !draft.value.detail.trim()) && parts.rest.trim()) {
      draft.value.detail = parts.rest.trim().slice(0, 60);
    }
  }
  if ((replace || !draft.value.detail.trim()) && p.name) {
    draft.value.detail = p.name.slice(0, 60);
  }
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
    // 海外号码不是 11 位大陆格式 —— 端上也要按国家放宽，否则按钮一直是灰的
    (overseas.value ? /^\+?\d{3,20}$/.test(String(draft.value.phone)) : isPhone(String(draft.value.phone ?? ""))) &&
    notBlank(draft.value.region) &&
    notBlank(draft.value.detail) &&
    /*
     * 门牌号**端上必填**。后端刻意没有 @NotBlank：还没更新的老版本 App 压根不发这个字段，
     * 后端要着的话它连「改个手机号」都保存不了。所以这条闸只在这里。
     */
    notBlank(draft.value.houseNo ?? ""),
);

/**
 * 「重选」—— **直接开地图**，与「新增地址」那一颗同一条路。
 *
 * <p>两处都从地图起步，用户才不用先记住「哪个入口给的是地图、哪个是列表」。
 * 而且在地图上点一下拿回来的一定带坐标，那正是这一整条链的全部收获。
 *
 * <p>取消就落到选择地点页（搜索与「附近」在那儿），**草稿留着** ——
 * 姓名手机他已经填过了，为了换个地点让他重填一遍是最让人恼火的那种。
 */
async function repick() {
  if (canChooseLocation()) {
    const r = await chooseLocation(
      draft.value.latE6 != null && draft.value.lngE6 != null
        ? { lat: draft.value.latE6 / 1e6, lng: draft.value.lngE6 / 1e6 }
        : null,
    );
    if (r.ok) {
      applyPicked(r.picked, true);
      return;
    }
  }
  uni.navigateTo({ url: ROUTES.addressPick });
}

async function save() {
  if (!valid.value) {
    uni.showToast({ title: String(t("address.invalid")), icon: "none" });
    return;
  }
  emit("saved", await api.saveAddress({ ...draft.value }));
}

/**
 * 预设标签。**存的是当前语言下的显示文案，不是码。**
 *
 * <p>本来该存 `HOME/WORK/SCHOOL` 再按词条渲染（这仓对枚举就是这个规矩）。
 * 没这么做的理由很具体：顶栏那个短名走的是 `location` store 的 `label` getter，
 * 而 `packages/shared/src/utils/locale.ts` 明确禁止 shared/store 反向依赖各端的 i18n。
 * 为一个**纯装饰、无任何逻辑匹配**的字段付这个代价不值。
 */
const TAG_PRESETS = ["tagHome", "tagWork", "tagSchool"] as const;

/**
 * 这个端有没有任何一条选点路。**同一个判断供两处用**：
 * 「新增」要不要进选点页，以及表单里的地址主体要不要设成只读。
 * 两处分开写的话，H5 上会出现「进不了选点页、地址主体却锁着」——他就永远存不了地址。
 */
const canPick = computed(() => canSearchPlaces() || canChooseLocation());

/**
 * 中国大陆以外。**整段换形状的唯一开关**。
 *
 * <p>关掉的：地点搜索、附近、地图选点、省市区拆分 —— 高德不覆盖海外，
 * 给一个点了搜不到东西的搜索框，比干脆没有更糟。
 * 打开的：Address line / City / State / 邮编，手机号带区号且不再写死 11 位。
 *
 * <p>存量地址读出来是 `CN`（库里那一列 NOT NULL DEFAULT 'CN'），
 * 所以这条判断对老数据天然成立，不需要回填。
 */
const overseas = computed(() => !!draft.value.countryCode && draft.value.countryCode !== "CN");

/**
 * 可选的国家/地区。**只有两个，这是有意的。**
 *
 * <p>多摆几个不花什么力气，但每多一个就是一句承诺 —— 用户选了美国，
 * 他会以为这条地址真能寄到。而「寄不寄得出去」取决于商家有没有那条线路，
 * 不取决于表单里有没有这一格。所以这里只留**确实在做的那两个**。
 *
 * <p>要加第三个时：先确认有商家在发那儿的货，再加。
 */
const COUNTRIES = [
  { code: "CN", cc: "86" },
  { code: "AE", cc: "971" },
] as const;

/**
 * 国家/地区选择器**先不露出来**（2026-09-18）。
 *
 * <p>整套能力都在（库里三列、表单的海外形状、手机号按国家挑判据、
 * 后端的 round-trip 用例），只是入口先收着 —— 露出来就是一句承诺：
 * 用户选了阿联酋会以为这条地址真能寄到，而那取决于有没有商家在发那儿的货。
 *
 * <p><b>放开时改这一个常量就够了</b>，不用回头再接一遍线。
 * 放开之前先确认：有商家的经营范围覆盖到那儿，且运费算得出来。
 *
 * <p>⚠️ 关着的这一半才是生产常态 —— 下面那条守卫两向都钉：
 * 关着时选择器不许渲染，而 `overseas` 那条分支必须还在（不然「将来再放开」
 * 会变成「将来重写一遍」）。
 */
const SHOW_COUNTRY_PICKER = false;

/**
 * 换国家时**顺手把区号也换了**，但不覆盖他已经改过的。
 *
 * <p>不换的话，一个选了美国的人手机号前面还挂着 +86 —— 而那条地址
 * 保存得下、看起来也正常，只有发短信那一刻才发现发不出去。
 */
function pickCountry(code: string, cc: string) {
  const prev = COUNTRIES.find((c) => c.code === draft.value.countryCode);
  if (!draft.value.phoneCc || draft.value.phoneCc === prev?.cc) {
    draft.value.phoneCc = cc;
  }
  draft.value.countryCode = code;
}
</script>

<template>
    <!--
      **国家/地区在最上面**：它决定了下面整段长什么样，摆在后面的话
      用户会先填一半省市区、再发现自己要选的是美国。
    -->
    <view v-if="SHOW_COUNTRY_PICKER" class="countryrow sh-row sh-wrap">
      <text
        v-for="c in COUNTRIES"
        :key="c.code"
        class="sh-chip"
        :class="{ 'sh-chip--primary': draft.countryCode === c.code }"
        @tap="pickCountry(c.code, c.cc)"
      >{{ $t(`country.${c.code}`) }}</text>
    </view>

    <!--
      **海外：整段换形状。** 地点搜索、附近、地图选点、省市区拆分全关掉 ——
      高德不覆盖海外，给一个点了搜不到东西的搜索框比干脆没有更糟。
      一期只保证「存得下、说得清、寄得出」，不做地址格式本地化。
    -->
    <template v-if="overseas">
      <input maxlength="96" v-model="draft.detail" class="field__input"
             :placeholder="$t('address.line1')" />
      <input maxlength="96" v-model="draft.houseNo" class="field__input"
             :placeholder="$t('address.line2')" />
      <view class="namerow sh-row">
        <input maxlength="64" v-model="draft.city" class="field__input sh-fill"
               :placeholder="$t('address.cityField')" />
        <input maxlength="64" v-model="draft.province" class="field__input sh-fill"
               :placeholder="$t('address.stateField')" />
      </view>
      <input maxlength="16" v-model="draft.postalCode" class="field__input"
             :placeholder="$t('address.postalCode')" />
    </template>

    <template v-else>
    <!--
      **所在位置摆在最上面，而且已选点时收成一张只读的卡。**

      此前这里是两行输入框：省市区那一行挤着「请选择 / 地图选点 / 微信地址」
      三个按钮（输入框只剩指甲盖那么宽），底下再一行详细地址。
      而能选点的端上这两样**本来就是选点填进来的、改不了** ——
      摆成输入框只会让人以为可以改，改了坐标也不跟着动。

      没有选点能力的端（H5）保持原来那两行可输入：否则他连存量地址都改不了。
    -->
    <view v-if="picked" class="sh-notice sh-notice--muted placecard sh-row">
      <view class="sh-fill placecard__body">
        <text class="txt-strong placecard__name">{{ draft.detail }}</text>
        <text class="txt-caption placecard__region">{{ draft.region }}</text>
      </view>
      <text class="txt-caption txt-primary placecard__act" @tap="repick">
        {{ $t("address.repickPlace") }}
      </text>
    </view>
    <template v-else>
      <view class="regionrow sh-row">
        <input
          maxlength="96"
          v-model="draft.region"
          class="field__input sh-fill"
          :placeholder="$t('address.region')"
          @blur="onRegionInput"
        />
        <text class="txt-caption regionrow__pick" @tap="pickingRegion = true">{{ $t("address.regionSelect") }}</text>
        <text v-if="canPick" class="txt-caption regionrow__pick" @tap="pickOnMap">
          {{ $t("address.pick") }}
        </text>
        <text v-if="canWx" class="txt-caption regionrow__pick" @tap="fillFromWx">{{ $t("address.fromWx") }}</text>
      </view>
      <text v-if="regionUnsplit" class="sh-hint">{{ $t("address.regionIncomplete") }}</text>
      <!--
        **能选点的端上仍然只读**，改要回选点页。它是跟坐标一起来的，
        在这里随手改几个字坐标不会跟着动 —— 于是「文字写着 A、坐标指着 B」，
        而页面上完全看不出来。没有任何选点路的端（H5）保持可输入，
        否则他连存量地址都改不了。
        （这一条我重排表单时弄丢过一次，守卫当场抓了回来。）
      -->
      <input
        maxlength="255"
        v-model="draft.detail"
        class="field__input"
        :disabled="canPick"
        :placeholder="$t('address.detail')"
      />
    </template>
    <input
      maxlength="40"
      v-model="draft.houseNo"
      class="field__input"
      :placeholder="$t('address.houseNo')"
    />
    </template>
    <!--
      姓名与手机**同一行**：两个都是短字段，各占一整行会把这张表拉得很长，
      而表越长「还要填多少」越看不到头。
    -->
    <view class="namerow sh-row">
      <input maxlength="64" v-model="draft.name" class="field__input sh-fill"
             :placeholder="$t('address.name')" />
      <input
        v-model="draft.phone"
        class="field__input sh-fill"
        type="number"
        maxlength="11"
        :placeholder="$t('address.phone')"
      />
    </view>
    <text v-if="!user.user?.phone" class="txt-caption txt-primary bindphone" @tap="phoneGate = true">
      {{ $t("address.bindPhone") }}
    </text>
    <phone-gate :visible="phoneGate" :suggest="draft.phone" @done="onPhoneBound" @close="phoneGate = false" />
    <!--
      标签：预设三个点一下就填好，旁边仍留一个输入框。
      **不做成「预设/自定义」两种模式** —— 输入框始终是唯一真源，
      chip 只是快捷方式，于是没有「我现在处在哪种模式」这个问题。
    -->
    <view class="tagrow sh-row">
      <text
        v-for="k in TAG_PRESETS"
        :key="k"
        class="sh-chip"
        :class="{ 'sh-chip--primary': draft.tag === $t(`address.${k}`) }"
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
    <view v-if="!picked" class="sh-notice sh-notice--muted nocoord sh-row sh-row--between">
      <text class="txt-caption nocoord__text">{{ $t("address.noCoordHint") }}</text>
      <text v-if="canPick" class="txt-caption txt-primary" @tap="pickOnMap">
        {{ $t("address.pick") }}
      </text>
    </view>


    <view class="sh-btn form__save" :class="{ 'is-disabled': !valid }" @tap="save">
      {{ $t("common.confirm") }}
    </view>
</template>

<style scoped>
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
/* 共用的 `.field__input`（88rpx 高 / md 圆角 / faint 底 / 30rpx）已经是这个形状 ——
   此前这里把它重写了一遍，而且字号写成 26rpx，比 base.css 的 30rpx 小两档。
   这里只留这一页特有的：字段之间的纵向间距。 */
.field__input {
  margin-top: 16rpx;
}
/* 所在位置：已选点时收成一张只读的卡 —— 摆成输入框会让人以为可以改 */
.placecard {
  gap: 16rpx;
}
.placecard__body {
  min-width: 0;
}
.placecard__name {
  display: block;
}
.placecard__region {
  display: block;
  margin-top: 4rpx;
}
.placecard__act {
  flex-shrink: 0;
}
/* 姓名与手机同行：两个都是短字段，各占一行会把表拉得看不到头 */
.countryrow {
  gap: 16rpx;
  margin-bottom: 16rpx;
}
.phonecc {
  width: 140rpx;
  flex-shrink: 0;
}
.namerow {
  gap: 16rpx;
}
/* 手机号栏下面的绑定入口：靠行尾，与手机号那一半对齐（阿语下跟着翻） */
.bindphone {
  display: block;
  margin-top: 12rpx;
  text-align: end;
}
.tagrow {
  gap: 12rpx;
  margin-top: 16rpx;
}
.nocoord {
  margin-top: 24rpx;
  gap: 16rpx;
}
.nocoord__text {
  flex: 1;
}
.switchrow {
  margin-top: 28rpx;
}
.form__save {
  margin-top: 36rpx;
}
</style>
