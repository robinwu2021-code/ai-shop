<script setup lang="ts">
// 地址簿：列表 + 新增/编辑（同页内弹层，不再多开一页 —— 表单只有 5 个字段）。
// `picking=1` 时从结算页进入，选中即回填并返回。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { Address } from "@shared/types";
import { chooseLocation, chooseWxAddress } from "@shared/ports/location";
import { confirm } from "@ai-shop/ui/prompt";
import { isPhone, notBlank } from "@shared/utils/validate";
import { isCompleteRegion, joinRegion, splitRegion } from "@shared/utils/region";

const { t } = useI18n();

const list = ref<Address[]>([]);
const picking = ref(false);
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
    notBlank(draft.value.detail),
);

async function load() {
  list.value = await api.addressList();
}

function openNew() {
  draft.value = {
    name: "", phone: "", region: "", province: "", city: "", district: "",
    detail: "", isDefault: !list.value.length, tag: "", latE6: null, lngE6: null,
  };
  editing.value = true;
}

function openEdit(a: Address) {
  draft.value = { ...a };
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

async function setDefault(a: Address) {
  list.value = await api.setDefaultAddress(a.addressId);
}

/** 从结算页进来时，点一条即选中并返回 */
async function pick(a: Address) {
  if (!picking.value) return;
  await api.setDefaultAddress(a.addressId);
  uni.navigateBack();
}

onLoad((q) => {
  picking.value = q?.picking === "1";
  load();
});
</script>

<template>
  <sh-scaffold title-key="address.title">
    <view v-for="a in list" :key="a.addressId" class="sh-card card" @tap="pick(a)">
      <view class="card__head sh-wrap">
        <text class="txt-strong">{{ a.name }}</text>
        <text class="txt-caption sh-num">{{ a.phone }}</text>
        <text v-if="a.tag" class="txt-caption sh-chip tiny">{{ a.tag }}</text>
        <text v-if="a.isDefault" class="txt-caption sh-chip sh-chip--primary tiny">
          {{ $t("address.default") }}
        </text>
      </view>
      <text class="txt-caption card__addr">{{ a.region }} {{ a.detail }}</text>

      <view class="card__ops">
        <text v-if="!a.isDefault" class="txt-caption op txt-primary" @tap.stop="setDefault(a)">
          {{ $t("address.setDefault") }}
        </text>
        <text class="txt-caption op txt-primary" @tap.stop="openEdit(a)">{{ $t("address.edit") }}</text>
        <text class="txt-caption op is-danger" @tap.stop="remove(a)">{{ $t("address.remove") }}</text>
      </view>
    </view>

    <sh-empty bare v-if="!list.length" :text='$t("address.empty")'></sh-empty>

    <sh-actionbar :pad="160">
      <view class="sh-btn" @tap="openNew">{{ $t("address.add") }}</view>
    </sh-actionbar>

    <!-- 编辑弹层 -->
    <sh-sheet
      :visible="editing"
      :title="String(draft.addressId ? $t('address.edit') : $t('address.add'))"
      @close="editing = false"
    >
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
        <input maxlength="255" v-model="draft.detail" class="field__input" :placeholder="$t('address.detail')" />
        <input maxlength="16" v-model="draft.tag" class="field__input" :placeholder="$t('address.tagPh')" />

        <view class="switchrow sh-row sh-row--between" @tap="draft.isDefault = !draft.isDefault">
          <text class="txt-sub switchrow__label txt-ink">{{ $t("address.asDefault") }}</text>
          <sh-switch :model-value="draft.isDefault"></sh-switch>
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
.switchrow {
  margin-top: 28rpx;
}
.sheet__save {
  margin-top: 36rpx;
}
</style>
