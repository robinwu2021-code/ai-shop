<script setup lang="ts">
// 地址簿：列表 + 新增/编辑（同页内弹层，不再多开一页 —— 表单只有 5 个字段）。
// `picking=1` 时从结算页进入，选中即回填并返回。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useLocationStore } from "@/stores/location";
import type { Address } from "@shared/types";
import { canChooseLocation, canChooseWxAddress, chooseLocation, chooseWxAddress, getLocationDetailed } from "@shared/ports/location";
import { readClipboard } from "@shared/ports/clipboard";
import { parsePastedAddress } from "@shared/utils/address-paste";
import { confirm } from "@ai-shop/ui/prompt";
import { isPhone, notBlank } from "@shared/utils/validate";
import { pickedAddress, pickedPlace, placeFrom } from "@/shared/address-pick";
import type { PlacePick } from "@/shared/address-pick";
import { canSearchPlaces } from "@shared/ports/geo-search";
import { ADDRESS_RULES, ROUTES } from "@shared/utils/constants";
import { isCompleteRegion, joinRegion, splitRegion } from "@shared/utils/region";

const { t } = useI18n();

const list = ref<Address[]>([]);
/** 首屏到过没有。**不是 `loading`** —— 那个含下拉刷新，刷新时把列表换成空态是另一个 bug */
const loaded = ref(false);
/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「你还没有收货地址」 */
const failed = ref(false);
const picking = ref(false);
const location = useLocationStore();
async function load() {
  try {
    list.value = await api.addressList();
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

/**
 * 「新增地址」**先去选点页**，让地址从一开始就带坐标。
 *
 * <p><b>但这个端给不了任何一条选点路时直接开表单</b>（H5：没有原生搜索、
 * 也没配地图 JS key）。否则那一页对他只剩一行「手动填写」，
 * 白挡一次点击，比改造前更差。
 */
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
/**
 * 当前位置的地名（「龙华区地域馆」）。**读 store 的单一真源，这一页不自己算。**
 * 取不到就是空串，界面回落到「当前位置」四个字 —— 宁可少一行，不要编一个地名。
 */
const locatedName = computed(() => location.here?.place?.name ?? "");

/**
 * 「我在哪」**走 store 的单一真源**，这一页不再自己定位、自己解析。
 *
 * <p>此前这儿有一份独立实现（`getLocationDetailed` + `resolveLocation`，
 * 地名取 `innermostName ?? nearestName`），而首页取的是另一套、
 * 选择地点页又是第三套。实测截图里两页并排显示的不是同一个位置：
 * 这一页写着「桂澜新村」（几天前绑的归属），选择地点页写着「使用当前位置」。
 *
 * <p>收敛之后，「重新定位」在任何一处点下去，三处同时变。
 */
async function detectHere() {
  const here = await location.ensureHere();
  // 模糊坐标（区级，误差 5 公里）匹配收货地址同样是噪音，一律不用
  if (!here || here.coarse) return;
  locatedAt.value = here.coords;
  locatedMatch.value = location.suggestNearest(here.coords)?.addressId ?? "";
}

/** 「重新定位」。与首页、选择地点页是同一个动作 */
async function relocate() {
  await location.relocate();
  await detectHere();
}

/**
 * 「存为收货地址」——把当前定位变成一条能下单的地址。
 *
 * <p>**新用户的第一条地址此前要走「选点 → 逐格填表」**，而他人就站在那儿，
 * 定位已经知道他在哪个小区。这一颗按钮把那一段省掉。
 *
 * <p>跳选点页并带 `useHere=1`，由它自己交回一条带省市区的地址 ——
 * 坐标变地址的拆法只能有一份，理由见那一页 onLoad 的注释。
 */
function saveHereAsAddress() {
  if (atLimit.value) {
    uni.showToast({ title: String(t("address.limitReached", { n: ADDRESS_RULES.maxCount })), icon: "none" });
    return;
  }
  /*
   * **走 store 里唯一的那一份。** 此前这儿先跳选择地点页（`?useHere=1`）
   * 再让它交回来，而下单页是直接带坐标进新建预填 —— 同一个字，两种流程。
   * 统一走后者：地点已经解析好了，再过一遍选点页是多余的一步。
   */
  if (!location.gotoSaveHere({ address: ROUTES.addressEdit })) {
    // 连坐标都还没有（H5、定位被拒）：照旧开空表单，至少不把人堵在这儿
    uni.navigateTo({ url: ROUTES.addressEdit });
  }
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

/**
 * 这个端有没有任何一条选点路。**决定「新增」要不要先进选点页** ——
 * 给不了任何一条的端（H5：没有原生搜索、也没配地图 JS key）直接开表单，
 * 否则那一页对他只剩一行「手动填写」，白挡一次点击。
 *
 * <p>表单里那个「地址主体要不要只读」是同一个判断，在 `biz-address-form` 里 ——
 * 两处读的是同一组端能力，不是各判各的。
 */
const canPick = computed(() => canSearchPlaces() || canChooseLocation());

/** 这个端能不能开地图选点。**只问一次** —— 它在一次运行里不会变 */
const canMap = canChooseLocation();

/** 到上限了。真正的闸在后端（老版本 App 不知道有这回事），这里只是提前说一声 */
const atLimit = computed(() => list.value.length >= ADDRESS_RULES.maxCount);

/**
 * 「新增地址」**默认直接开地图**。
 *
 * <p>在地图上点一下是这条路上最省的一步：名字叫不上来也指得出来，
 * 而且拿回来的一定带坐标 —— 那正是这一整条链的全部收获
 * （没坐标的地址：自送半径判不了、骑手导航打不开、推不出聚落）。
 *
 * <p><b>取消不回到原地，落到选择地点页。</b> 直接退回列表的话，
 * 搜索与「附近」就没有入口了 —— 而它们恰恰是「我知道小区叫什么」那条更快的路。
 *
 * <p>给不了地图的端（H5 没配 JS key）直接开表单：让他对着一个点不动的
 * 「地图选点」发呆，比没有更糟。
 */
async function addNew() {
  if (atLimit.value) {
    uni.showToast({ title: String(t("address.limitReached", { n: ADDRESS_RULES.maxCount })), icon: "none" });
    return;
  }
  if (!canMap) {
    uni.navigateTo({ url: canPick.value ? ROUTES.addressPick : ROUTES.addressEdit });
    return;
  }
  const r = await chooseLocation(location.here?.coords ?? null);
  if (!r.ok) {
    // 取消 / 不支持：都落到选择地点页，那儿还有搜索与「附近」
    uni.navigateTo({ url: `${ROUTES.addressPick}?next=edit` });
    return;
  }
  /*
   * 交给选择地点页那份 `placeFrom` 去拆省市区 —— **拆法只能有一处**。
   * 在这里再写一份，两处迟早给出不一样的省市区，而那种不一致在界面上
   * 看不出来，只会让「按区派单」偶尔落错。
   */
  pickedPlace.offer(placeFrom(r.picked));
  uni.navigateTo({ url: ROUTES.addressEdit });
}

/** 编辑一条：**跳整页**，不再在这一页开弹层 */
function openEdit(a: Address) {
  uni.navigateTo({ url: `${ROUTES.addressEdit}?addressId=${a.addressId}` });
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

/*
 * **这一页刻意不读那个一次性信箱了。**
 *
 * 此前这儿是 `onShow` 里 `pickedPlace.peek()`，非空就往新建页跳。
 * 而 `peek` 不消费 —— 只要信箱里还留着东西（选点页交回来的、或者一条
 * `manual`），这一页**每次显示都会再弹一次新建页**：用户从新建页返回，
 * 刚落到列表上又被弹走，看起来就是「自动跳到了别的页面」。
 *
 * 改法是把跳转交给发起方：选点页知道自己是被谁打开的（`next=edit`），
 * 选完直接 `redirectTo` 新建页，中间不经过这一页。
 * 于是这里既不需要 peek，也不需要 take —— 一个不该由它承担的职责被拿掉了。
 */
</script>

<template>
  <sh-scaffold title-key="address.title">
    <!--
      **当前位置收成一行，不是一块。**（PRD §6.1.0 + M3）
      早先空态给的是整块卡 + 整条实心大按钮，底部还有一条同样大的「新增地址」——
      一屏两个同等份量的主按钮。而「存为地址」不是这一页的主动作：
      这一页的主动作是「挑一条地址去下单」。

      有匹配的话不显示这一行 —— 那条地址就在下面，标着「你在这儿」。
    -->
    <biz-place-bar
      v-if="locatedAt && !locatedMatch"
      :name="locatedName"
      :sub="list.length ? String($t('address.noMatchHint')) : ''"
      :stale="location.placeStale"
      can-save
      @relocate="relocate"
      @save="saveHereAsAddress"
    ></biz-place-bar>

    <biz-address-card
      v-for="a in list"
      :key="a.addressId"
      :address="a"
      actions
      :here="a.addressId === locatedMatch"
      :active="location.active?.addressId === a.addressId"
      @tap="pick(a)"
      @edit="openEdit(a)"
      @remove="remove(a)"
      @default="setDefault(a)"
      @fix="openEdit(a)"
    ></biz-address-card>

    <!-- 空态只说事实：他现在就能逛，只是还不能下单。上面那张卡才是下一步 -->
    <sh-empty
      bare
      v-if="!list.length"
      :pending="!loaded"
      :failed="failed"
      :text='$t("address.empty")'
      :tip='locatedAt ? String($t("address.emptyHint")) : ""'
      @retry="load"
    ></sh-empty>

    <sh-actionbar :pad="160">
      <view class="sh-btn" :class="{ 'is-disabled': atLimit }" @tap="addNew">
        {{ atLimit ? $t("address.limitReached", { n: ADDRESS_RULES.maxCount }) : $t("address.add") }}
      </view>
    </sh-actionbar>


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
  padding: 4rpx 16rpx;
}
.card__addr {
  display: block;
  margin-top: 16rpx;
}
.card__ops {
  gap: 32rpx;
  margin-top: 20rpx;
  padding-top: 20rpx;
  /* 一条分隔线：上面是这条地址本身，下面是对它的操作 —— 没有线时两组读成一段 */
  border-top: var(--sh-hairline-soft);
}
.card__nocoord {
  margin-top: 16rpx;
  gap: 16rpx;
}
.card__fix {
  flex-shrink: 0;
}
/* 共用的 `.field__input`（88rpx 高 / md 圆角 / faint 底 / 30rpx）已经是这个形状 ——
   此前这里把它重写了一遍，而且字号写成 26rpx，比 base.css 的 30rpx 小两档。
   这里只留这一页特有的：字段之间的纵向间距。 */
.field__input {
  margin-top: 16rpx;
}
/*
 * 两档「当前位置」：
 * · herebig —— 一条地址都没有时，它是这一页的主角，实心按钮
 * · here    —— 已有地址时收成一行，**不许压过默认地址那张卡**
 */
.herebig {
  margin-bottom: 20rpx;
  border: 2rpx solid var(--sh-primary);
}
.herebig__head {
  gap: 8rpx;
}
.herebig__name {
  display: block;
  margin-top: 12rpx;
}
.herebig__save {
  margin-top: 24rpx;
}
.here {
  margin-bottom: 20rpx;
  gap: 16rpx;
}
.here__body {
  min-width: 0;
}
.here__name {
  display: block;
}
.here__sub {
  display: block;
  margin-top: 4rpx;
}
/* 次要动作：描边而不是实心 —— 这一行不该抢下面默认地址的注意力。
   颜色走库里的 .txt-primary（模板上挂着），这儿只管形状 */
.here__save {
  flex-shrink: 0;
  border: 2rpx solid var(--sh-primary);
  border-radius: 16rpx;
  padding: 8rpx 20rpx;
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
.namerow {
  gap: 16rpx;
}
.tagrow {
  gap: 12rpx;
  margin-top: 16rpx;
}
.pasterow {
  gap: 16rpx;
}
.pasterow__text {
  flex: 1;
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
.sheet__save {
  margin-top: 36rpx;
}
</style>
