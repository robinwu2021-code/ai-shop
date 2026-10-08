<script setup lang="ts">
import type { MasterData, MerchantApplyStatus, MyFission } from "@shared/types";
// 我的：登录入口 + 归属信息 + 外观与语言。
// 列表项之间用间距分块，不用分隔线（扁平色块风格）。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { phoneRequired, withPhone, requirePhoneOnEnter, onPhoneBound, onPhoneGateClose } from "@/shared/phone-required";
import { merchantApplyVisible, openWxCustomerService } from "@shared/ports";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import { useConfigStore } from "@/stores/config";
// 小程序才有原生客服会话；借 canNativeShare 判端（两者的条件编译判据相同）
import { canNativeShare } from "@shared/ports/share";
import PhoneGate from "@/components/phone-gate.vue";
import { useCommunityStore } from "@/stores/community";
import { useLocationStore } from "@/stores/location";
import { FEATURES, ROUTES } from "@shared/utils/constants";
import { confirm, pick } from "@ai-shop/ui/prompt";
import { isPhone } from "@shared/utils/validate";
import {
  INDUSTRY_OTHER,
  industryName as industryNameOf,
  industryNotOpen as isIndustryNotOpen,
  industryOptions as industryOptionsOf,
} from "@/shared/apply-industry";

const { t } = useI18n();

/** 构建版本号（vite define 注入）。见「帮助中心」那一行上方的注释 */
const buildVersion = __BUILD_VERSION__;
const user = useUserStore();
const config = useConfigStore();
/**
 * 商家版下载页。**与官网同一个地址** —— 官网那一页已经在维护安装包版本号，
 * 端上再写一份就会出现「官网 0.5.2、端上还指着 0.4.98」这种谁也不会发现的漂移。
 */
const MERCHANT_APP_URL = "https://hxmall.top/download";
/**
 * 招商电话。**写死在端上**：它是官网 `site.config.ts` 里的同一个号，
 * 但小程序没有读官网配置的路，为一个不会变的号加一个接口不值当。
 * 换号时两处一起改（官网 `contact.salesPhone`）。
 */
const SALES_PHONE = "18503088359";
/** 「我的」页的绑定入口。静默登录之后「有账号没手机号」是常态 */
const phoneGate = ref(false);
const community = useCommunityStore();
const location = useLocationStore();
const themeVisible = ref(false);
const points = ref(0);
const unread = ref(0);

function gotoLogin() {
  // 统一闸：这里要的其实是手机号（账号 openid 打开小程序就静默拿到了）
  void withPhone(() => gotoProfile());
}
/**
 * 商家运营入口。**只在「并进了 B 端分包」的那种构建里出现**
 * （`VITE_WITH_BIZ`，由 scripts/with-biz.mjs 注入）；普通 c-app 包里这张卡不存在，
 * 跳转目标 `/pkg-biz/_entry/index` 也不在包里。商家身份的闸在 `_entry` 里（无令牌先登录），
 * 所以这里只要「已登录的 C 端用户」就显示 —— 它是随手运营的入口，不是权限判定点。
 */
const withBiz = import.meta.env.VITE_WITH_BIZ === "1";
function gotoBizOps() {
  uni.navigateTo({ url: "/pkg-biz/_entry/index" });
}
/**
 * 退出登录。二次确认是必要的 —— 这一格紧挨着「帮助」，误触代价是重新走一遍登录。
 * 真正作废服务端会话在 store 里做（见 stores/user.ts 的说明）。
 */
async function onLogout() {
  // 用 callback 包 Promise，与本文件其他确认弹窗一致 —— uni 的 showModal
  // 在各端上并非都返回 Promise，直接 await 在小程序里拿不到 confirm
  const ok = await confirm({ title: String(t("me.logout")), hint: String(t("me.logoutConfirm")) });
  if (!ok) return;
  await user.logout();
  uni.reLaunch({ url: "/pages/home/index" });
}

/**
 * 注销账号。**微信对有账号体系的小程序要求提供这个入口**（上架审核会查）。
 *
 * <p>与「退出登录」隔开一段距离并用弱化的样式：两者一字之差、后果天差地别 ——
 * 退出登录再登回来就是；注销之后同一个微信进来是**一个全新账号**，
 * 旧账号的订单、卡券、积分他都再也看不到。
 *
 * <p>确认框把后果逐条说出来，而不是「确定要注销吗？」——
 * 那句话没有给他任何判断依据。
 */
async function onDeregister() {
  const ok = await confirm({ title: String(t("me.deregister")), hint: String(t("me.deregisterConfirm")), confirmText: String(t("me.deregisterYes")), danger: true });
  if (!ok) return;
  try {
    await api.deregister();
    await user.logout();
    uni.showToast({ title: String(t("me.deregisterDone")), icon: "none" });
    setTimeout(() => uni.reLaunch({ url: "/pages/home/index" }), 1200);
  } catch (e) {
    /*
     * 有未完成订单时后端回 70028。**要把他送到订单列表去** ——
     * 只说「还有未完成的订单」而不给入口，他得自己翻。
     */
    if ((e as { code?: number }).code === 70028) {
      void confirm({
        title: String(t("me.deregisterBlocked")),
        hint: String(t("me.deregisterBlockedTip")),
        confirmText: String(t("me.viewOrders")),
      }).then((ok) => {
        if (ok) uni.switchTab({ url: "/pages/order/index" });
      });
      return;
    }
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 位置就是地址 —— 选社区自提点那一页已经删了（买家不再挑点） */
function gotoProfile() {
  uni.navigateTo({ url: ROUTES.profile });
}

/** 头像是个能喂给 <image> 的地址吗。存量里还有 emoji 与空值 */
function isImageUrl(v?: string): boolean {
  return !!v && (v.startsWith("http") || v.startsWith("/"));
}

/**
 * 头部显示的名字。
 *
 * <p>还没设过昵称时显示的是「去设置昵称」而不是那个占位名 ——
 * 占位名看起来就是个真名字，那正是以前没人改昵称的原因（线上 23/23 都是它）。
 */
const displayName = computed(() =>
  user.user?.nicknameSet ? user.user?.nickname : String(t("profile.nicknameCta")),
);

function gotoCommunity() {
  uni.navigateTo({ url: ROUTES.address });
}

function gotoPoints() {
  uni.navigateTo({ url: ROUTES.points });
}

function gotoMessages() {
  uni.navigateTo({ url: ROUTES.messages });
}

function gotoMemberships() {
  uni.navigateTo({ url: ROUTES.myMemberships });
}

function gotoCoupons() {
  uni.navigateTo({ url: ROUTES.coupons });
}

/**
 * 邀请有礼（§3.1）。**取不到就当没有活动** —— 这一条是锦上添花，
 * 不能因为它把「我的」整页点亮成失败态（领券条同一条取舍）。
 */
const fission = ref<MyFission | null>(null);

/**
 * 小程序上才有原生客服会话（`open-type="contact"`）。
 * 其余端显示邮箱 —— 画一颗点了没反应的按钮比没有按钮更糟。
 */
const nativeContact = canNativeShare();

/**
 * 配齐了就走**微信客服**（企业微信那款），否则退回小程序原生客服会话。
 * 判据是 store 的 `wxKfReady`：`corpId` 与 `url` **两个都要有** ——
 * 半截参数调过去失败是静默的，界面上与「压根没配」一模一样。
 */
const wxKf = computed(() => nativeContact && config.wxKfReady);

/**
 * 打开微信客服。**整段必须同步** —— 这个 API 在 iOS 上要求由用户手势直接触发，
 * 中间插一次 await（比如现拉配置）就会被判「并非点击触发」而失败；
 * Android 却能过，于是那样写的代码只在 iOS 真机上才现形。
 * 配置是冷启动就拿到的，所以这里点开即用。
 */
function openWxKf() {
  openWxCustomerService(config.customerService, () => {
    uni.showToast({ title: String(t("me.contactFailed")), icon: "none" });
  });
}
/** 平台邮箱。与官网页脚同一个地址，改了两处都要改（官网在 site.config.ts） */
const PLATFORM_EMAIL = "hello@hxmall.top";

function gotoInvite() {
  uni.navigateTo({ url: ROUTES.invite });
}

function gotoCards() {
  uni.navigateTo({ url: ROUTES.cards });
}

function gotoOrders() {
  uni.navigateTo({ url: ROUTES.orders });
}

function gotoAddress() {
  uni.navigateTo({ url: ROUTES.address });
}

function gotoFavorites() {
  uni.navigateTo({ url: ROUTES.favorites });
}

function gotoMyGroups() {
  uni.navigateTo({ url: ROUTES.myGroups });
}

function gotoGroups() {
  uni.navigateTo({ url: ROUTES.groups });
}

function gotoVisited() {
  uni.switchTab({ url: ROUTES.merchants });
}

/**
 * 点开店卡片。
 *
 * <p><b>已经报过名的人不该再看到一张空表</b>：此前不管有没有意向单都打开新建表单，
 * 他填完一遍，提交时才被后端的「一人一份」唯一键拒掉 —— 白填一次，而且看不出为什么。
 * 有单就进查看态，改不改由那一屏上的状态决定。
 */
async function applyMerchant() {
  if (applyStatus.value) {
    applyViewVisible.value = true;
    /*
     * **查看态也要主数据**：那一屏上的「店铺类型」显示的是行业的**名字**，
     * 而名字只在主数据里。此前只有打开报名表那条路去拉它，于是直接点进查看态的人
     * 看到的是裸码 `FRESH` —— 界面没报错、也不空，只是把程序标识符摆给了店主看。
     * 2026-09-29 在 H5 mock 上截图时撞见的：单测断言的是取名函数本身，
     * 它拿到空列表时按约定回退成码，那一步是对的；错的是这一屏没把料备齐。
     *
     * 不 await：拉到之前先显示码，拉到之后 computed 自己跟着变 ——
     * 等它会让点开这一屏多一次网络往返的延迟。
     */
    void ensureMasterData();
    return;
  }
  await openApplyForm(null);
}

/**
 * 打开报名表。`from` 非空 = 在已有那份的基础上改（或驳回后重提），预填它的内容。
 *
 * <p>联系电话只在空着时兜自己的号（本人号码不脱敏）—— 填了一半关掉再开，不盖他写过的。
 */
async function openApplyForm(from: MerchantApplyStatus | null) {
  if (from) {
    mForm.value = {
      name: from.name ?? "",
      contactPhone: from.contactPhone ?? "",
      category: from.category ?? "",
      industry: from.industry ?? "",
      industryNote: from.industryNote ?? "",
    };
  }
  if (!mForm.value.contactPhone && user.user?.phone) mForm.value.contactPhone = user.user.phone;
  applyViewVisible.value = false;
  merchantVisible.value = true;
  await ensureMasterData();
}

/** 查看态：报过名的人点进来看到的是自己填过什么、审到哪一步 */
const applyViewVisible = ref(false);

/**
 * 查看态上逐行显示的内容。**与报名表一一对应** —— 表里问了什么，这里就回显什么，
 * 多一行少一行都会让人怀疑「我当时是不是填了别的」。
 *
 * <p>行业显示的是名字不是码：`RETAIL` 对店主没有意义。主数据还没拉到时退回码本身，
 * 不显示空 —— 空格子看着像「这一项没填」。
 */
const intentFields = computed(() => {
  const a = applyStatus.value;
  if (!a) return [] as { k: string; v: string }[];
  /*
   * 从**意向口径**里找名字：进件口径里没有未开放的那几档，
   * 报了餐饮的人会在查看态看到裸码「CATERING」。
   */
  const industryLabel = industryNameOf(industryOptions.value, a.industry);
  const industryLine = a.industryNote ? `${industryLabel}· ${a.industryNote}` : industryLabel;
  return [
    { k: String(t("merchant.shopName")), v: a.name },
    { k: String(t("merchant.intentIndustry")), v: industryLine },
    { k: String(t("merchant.intentCategory")), v: a.category },
    { k: String(t("merchant.phone")), v: a.contactPhone },
  ].filter((f) => !!f.v);
});

/**
 * 正在改的那一份。空 = 这次是新建。
 *
 * <p>驳回后重提**走新建**而不是改 —— 后端状态机里 REJECTED 是终态，
 * 重提是新开一份单（旧单留着作记录）。
 */
const editingApplyNo = ref<string | null>(null);

/** 只有待审核能改。运营受理后改了的话，他看的与库里存的不是同一份 */
const applyEditable = computed(() => applyStatus.value?.status === "PENDING");

/** 驳回后可以重新报一份 */
const applyRejected = computed(() => applyStatus.value?.status === "REJECTED");

function editApply() {
  editingApplyNo.value = applyStatus.value?.applyNo ?? null;
  openApplyForm(applyStatus.value);
}

function resubmitApply() {
  // 重提 = 新开一份，但内容从被驳回那份预填 —— 让他改那几处，而不是从头再填一遍
  editingApplyNo.value = null;
  openApplyForm(applyStatus.value);
}

const merchantVisible = ref(false);
const mForm = ref({
  name: "",
  contactPhone: "",
  /** 经营范围（后端字段名 category）。「水果」「粮油」这一类，一句话说清卖什么 */
  category: "",
  /**
   * 店铺类型（后端字段名 industry）。**决定能不能以小微主体进件**（微信白名单按行业给），
   * 也是 points_forced 的来源。此前这张表没有这个字段，
   * 于是所有从 C 端入驻的商家 industry 恒空 —— 进件时才发现主体选错了。
   */
  industry: "",
  /**
   * 「其他」下自己写的行业（V360）。**只在选了「其他」时问** ——
   * `sys_industry` 只有七个大类，而意向表要收的正是归不进大类的那些。
   */
  industryNote: "",
});

/**
 * 主体类型（个人 / 个体户 / 企业）**不在这一屏问**（2026-09-28）。
 *
 * <p>不是省一格那么简单：它受行业白名单管控，端上选错了要到进件那一步才炸，
 * 而报名的人多半分不清「个人经营者」和「个体工商户」。后端 subject 传空时
 * {@code requireSubjectAllowedByIndustry} 直接放行（canonical == null 即 return），
 * 主体由运营在审核核营业执照时定 —— 那时候信息才是全的。
 */

/** 主数据：行业与主体都从服务端取，微信放开白名单时不用发版 */
const master = ref<MasterData | null>(null);
/**
 * 拉一次主数据。**两条路都要**：打开报名表（渲染行业选项）与直接进查看态（把码翻成名字）。
 *
 * <p>只在空时拉。⚠️ 这个判断在 App 上的有效期是**整个进程**（原生壳比一次页面加载活得久，
 * 见 [[app-process-outlives-one-shot-loads]]）—— 运营那天放开一个新行业，
 * 没杀过进程的人就看不到它。这里可以接受：它是取值域，而入驻是低频动作；
 * 真要即时跟上就得改成每次拉，代价是每次点开都多一次往返。
 * 拉失败留 null：行业那一格退回显示码，比整屏卡住好。
 */
async function ensureMasterData() {
  if (!master.value) master.value = await api.masterData().catch(() => null);
}
const industries = computed(() => master.value?.industries ?? []);
/**
 * 报名这一屏用的是**意向口径**（`intentIndustries`），不是进件口径（`industries`）。
 * 判断在 `@/shared/apply-industry` 里 —— 那三条是纯函数，抽出来才测得动。
 */
const industryOptions = computed(() => industryOptionsOf(master.value));
/** 选中的这一档平台还没开放 —— 给一句话，不禁用、不拦提交 */
const industryNotOpen = computed(() => isIndustryNotOpen(industryOptions.value, mForm.value.industry));

/** 报名表上那一行显示的名字。没选时空串，由模板退回提示语 */
const industryLabel = computed(
  () => industryOptions.value.find((i) => i.industry === mForm.value.industry)?.name ?? "",
);

/**
 * 选店铺类型。**开在报名表那层弹层之上**（`stacked`）——
 * 不叠的话两层同 z-index，顺序只由 DOM 决定，而那是会随层叠上下文翻过来的。
 *
 * <p>取消（`null`）不动已选的那一项：他点开只是想看看有哪几类。
 */
async function pickIndustry() {
  const opts = industryOptions.value;
  const i = await pick({
    title: String(t("merchant.intentIndustry")),
    items: opts.map((o) => o.name),
    selected: opts.findIndex((o) => o.industry === mForm.value.industry),
    stacked: true,
  });
  if (i !== null && opts[i]) mForm.value.industry = opts[i].industry;
}
/** 选了「其他」才问手填 */
const industryIsOther = computed(() => mForm.value.industry === INDUSTRY_OTHER);

const mValid = computed(
  () =>
    mForm.value.name.trim() &&
    isPhone(mForm.value.contactPhone.trim()) &&
    mForm.value.category.trim(),
);

/**
 * 入驻进度。**此前提交完这一行还写着「个体户/企业均可」** ——
 * 商家不知道审到哪一步，只能打电话问运营。
 *
 * <p>没申请过时**不兜一句解释**（2026-09-28）：那一句是「个体户 / 企业均可」，
 * 属于说明而不是状态，而卡片上的副标题已经说了同一件事。
 * 模板里用 `v-if="applyStatus"` 控制这一格显不显示，所以这里返空串就够。
 */
const applyStatus = ref<MerchantApplyStatus | null>(null);
const applyStatusText = computed(() =>
  applyStatus.value ? String(t(`merchant.applyStatus.${applyStatus.value.status}`)) : "",
);

async function submitMerchant() {
  if (!mValid.value) return;
  /*
   * **手机号要以字符串发出去**：两个号码框都是 `type="number"`（为了弹数字键盘），
   * 而 H5 上 v-model 会把值转成数字 —— 后端收的是 String（见 phone-gate 里同一个坑）。
   *
   * **只发这四个字段**：其余（主体类型、联系人、简介、推荐人）端上不再问，
   * 展开整个 mForm 的话，删掉的字段会以空串发上去 —— 空串与「没填」在运营端不是一回事。
   */
  const payload = {
    name: mForm.value.name.trim(),
    category: mForm.value.category.trim(),
    industry: mForm.value.industry,
    /*
     * 手填行业只在选了「其他」时发。别的行业下发上去后端也会置空（V360），
     * 但端上先收口一次 —— 少发一个对不上的值，运营端就少一次「该信哪个」。
     */
    industryNote: industryIsOther.value ? mForm.value.industryNote.trim() : undefined,
    contactPhone: String(mForm.value.contactPhone ?? "").trim(),
  };
  /*
   * 改已有那份 vs 新报一份。**驳回后重提走新建** —— 后端状态机里 REJECTED 是终态，
   * 重提是新开一份单，旧单留着作记录。
   */
  applyStatus.value = editingApplyNo.value
    ? await api.updateMerchantApply(editingApplyNo.value, payload)
    : await api.merchantApply(payload);
  const wasEdit = !!editingApplyNo.value;
  editingApplyNo.value = null;
  merchantVisible.value = false;
  /*
   * 改完回查看态，新报完给下载引导。
   * 改完再弹一次「去装 App」是重复的 —— 他上次提交时已经看过那一屏了。
   */
  if (wasEdit) {
    applyViewVisible.value = true;
    uni.showToast({ title: String(t("merchant.applyUpdated")), icon: "none" });
    return;
  }
  /*
   * 提交成功 → **引导去装商家版 App**（2026-09-28 拍板的后半句）。
   *
   * <p>不是客套：经营动作（上架、改价、接单、发货、看账）都在 App 里，
   * 小程序这一侧只承接「报名」这一步。不说这句的话，他提交完会在小程序里
   * 找「我的店铺」—— 而那里什么都没有，看起来像是没提交成功。
   *
   * <p>小程序里**不能直接下载 APK**（微信拦），所以给的是官网商家端地址，
   * 让他复制到手机浏览器打开；H5/App 上直接跳。
   */
  appDownloadVisible.value = true;
}

/**
 * 这台设备是不是 iOS。**决定默认展开哪一档下载** ——
 * 让 iPhone 用户先看到安卓包，他会以为没有 iOS 版。
 *
 * <p>小程序与 App 上 `platform` 是 ios / android；H5 上是 devtools 之外的值，
 * 认不出来就按安卓走（安卓包是现在唯一真实存在的那一个）。
 */
const isIos = ref(false);

/**
 * 当前系统那一档的下载地址。**两档都为空时退回官网下载页** ——
 * 那一页上两个平台都有，永远有东西可给，而不是显示一个空按钮。
 */
const appLink = computed(() => {
  const m = config.merchantApp;
  const mine = isIos.value ? m.ios : m.android;
  return mine || m.android || m.ios || MERCHANT_APP_URL;
});

/** 另一档（本机系统之外那个）。空 = 那一档还没有，整行不显示 */
const otherAppLink = computed(() => (isIos.value ? config.merchantApp.android : config.merchantApp.ios));

/** 报名途中卡住时打过来。三端都走得通 —— 小程序上是微信原生的拨号确认 */
function callSales() {
  uni.makePhoneCall({ phoneNumber: SALES_PHONE });
}

/** 提交完那一屏：告诉他下一步在 App 里做 */
const appDownloadVisible = ref(false);


/*
 * 认一次系统。**放在 onShow 外面只跑一次** —— 设备不会中途变，
 * 而 App 的进程比一次页面加载活得久，每次回到这一页都问一遍是白花。
 */
try {
  isIos.value = String(uni.getSystemInfoSync().platform ?? "").toLowerCase() === "ios";
} catch {
  // 取不到就按安卓走 —— 安卓包是现在唯一真实存在的那一个
}

onShow(() => {
  if (user.isLogin) {
    user.loadProfile();
    // 没申请过返回 null，不是错误 —— 失败也不该影响整页
    api.myMerchantApply().then((a) => (applyStatus.value = a)).catch(() => {});
    // 只取一个数 —— 拉整个列表数未读是把带宽当角标用
    api.unreadMessages().then((n) => (unread.value = n)).catch(() => {});
    // 没有在跑的活动时后端返回 null，那条入口就整条不显示
    api.myFission().then((f) => (fission.value = f)).catch(() => (fission.value = null));
  } else {
    /*
     * **未登录必须清零**：这一条此前写在 isLogin 块外面 ——
     * 于是未登录的人也会看到「N 条未读」，而那不是他的消息；
     * 接真后端时还会白挨一个 401（并可能触发全局登出跳转）。
     * 不清零的话，登出之后旧数字还挂在那儿。
     */
    unread.value = 0;
    // 登出之后不该还挂着别人的邀请进度
    fission.value = null;
  }
  if (FEATURES.points) api.pointAccount().then((a) => (points.value = a.balance));
  // 进页即弹（2026-10-08 拍板）
  void requirePhoneOnEnter();
});
</script>

<template>
  <sh-scaffold title-key="tab.me" tab="me">
    <!--
      登录后点这张卡进个人资料页（C-AC-08）。
      **此前登录后整张卡点了没反应** —— 而每个人的名字都是建户时给的占位名
      （线上 23/23），也就是说这个平台上没有任何人改过昵称：不是没人想改，是没有入口。
    -->
    <view class="sh-card head sh-row" @tap="user.isLogin ? gotoProfile() : gotoLogin()">
      <!--
        头像可能是 URL、emoji 或空。三种都要显示得出来 ——
        把一个 emoji 喂给 <image> 的结果是一个碎图标，而不是一张头像。
      -->
      <image
        v-if="isImageUrl(user.user?.avatar)"
        class="head__avatar head__avatar--img"
        :src="user.user?.avatar"
        mode="aspectFill"
      />
      <text v-else class="head__avatar">{{ user.user?.avatar || "🙂" }}</text>
      <view class="sh-fill">
        <text class="txt-title head__name">
          {{ user.isLogin ? displayName : $t("me.login") }}
        </text>
        <!--
          静默登录之后**有账号但没手机号**是常态，此时旧写法显示的是一片空白 ——
          用户不知道那一行为什么空着，更不知道能点。
          现在空着的时候直接说「去绑定」，它就是入口。
        -->
        <text v-if="!user.isLogin" class="txt-caption head__sub">{{ $t("me.loginHint") }}</text>
        <text v-else-if="user.user?.phone" class="txt-caption head__sub">{{ user.user.phone }}</text>
        <text v-else class="txt-caption head__sub head__sub--action txt-primary" @tap.stop="phoneGate = true">
          {{ $t("me.bindPhone") }}
        </text>
      </view>
    </view>

    <view class="sh-cells">
      <!--
        「我的位置」：显示的是**位置**，不是自提点。
        自提点已经改成下单时按地址匹配（TDD-C端位置选择-地址取代自提点），
        这一行再显示某个点就是在说一件已经不存在的事。

        原来它下面还有一行「我的常去店」，取的是绑定自提点的承接商家 ——
        没有绑定自提点之后它恒为「—」。**一行永远是「—」比没有这一行更糟**：
        每次进来都要看一眼，每次都没有内容。删掉。
      -->
      <view class="sh-cell sh-row sh-row--between" @tap="gotoCommunity">
        <text class="txt-body cell__label">{{ $t("me.myPlace") }}</text>
        <text class="txt-caption cell__value">{{ location.label || $t("me.unset") }}</text>
      </view>
    </view>

    <!-- 交易：订单、券、地址 —— 买东西相关的都在这一组 -->
    <view class="sh-cells">
      <view class="sh-cell sh-row sh-row--between" @tap="gotoMessages">
        <text class="txt-body cell__label">{{ $t("message.title") }}</text>
        <!--
          未登录时**什么都不显示**。显示「已全部阅读」是在说一句假话：
          它暗示这个人有消息且都读过了，而他还没登录，平台根本不知道他是谁。
          与上面「我的常去店」未登录时显示「—」是同一个口径。
        -->
        <text v-if="user.isLogin" class="txt-caption cell__value sh-num">
          {{ unread ? $t("message.unread", { n: unread }) : $t("message.allRead") }}
        </text>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="gotoOrders">
        <text class="txt-body cell__label">{{ $t("orders.title") }}</text>
      </view>
      <!-- 我的收藏（原型 g08）：商品与店铺两栏 -->
      <view class="sh-cell sh-row sh-row--between" @tap="gotoFavorites">
        <text class="txt-body cell__label">{{ $t("favorites.title") }}</text>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="gotoCoupons">
        <text class="txt-body cell__label">{{ $t("coupon.title") }}</text>
      </view>
      <!--
        邀请有礼（§3.1）。**没有在跑的活动时整条不出现** ——
        一个点进去说「暂无活动」的入口比没有入口更糟。
        右侧显示的是「已邀请 N 人」而不是活动名：他来这一屏是想知道自己的进度。
      -->
      <view v-if="fission" class="sh-cell sh-row sh-row--between" @tap="gotoInvite">
        <text class="txt-body cell__label">{{ $t("invite.title") }}</text>
        <text class="txt-caption cell__value sh-num">
          {{ $t("invite.entryHint", { n: fission.myInvited }) }}
        </text>
      </view>
      <!-- 会员与消息：**退订入口必须在显眼处**，藏起来的开关等于没有 -->
      <view class="sh-cell sh-row sh-row--between" @tap="gotoMemberships">
        <text class="txt-body cell__label">{{ $t("myMembership.title") }}</text>
      </view>
      <view v-if="FEATURES.cards" class="sh-cell sh-row sh-row--between" @tap="gotoCards">
        <text class="txt-body cell__label">{{ $t("cards.title") }}</text>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="gotoAddress">
        <text class="txt-body cell__label">{{ $t("address.title") }}</text>
      </view>
      <view v-if="FEATURES.points" class="sh-cell sh-row sh-row--between" @tap="gotoPoints">
        <text class="txt-body cell__label">{{ $t("points.title") }}</text>
        <text class="txt-caption cell__value sh-num">{{ $t("points.entryHint", { n: points }) }}</text>
      </view>
    </view>

    <!-- 邻里：团、买过的店、入驻 —— 与「人」相关的一组 -->
    <view class="sh-cells">
      <!-- 我的拼团（p12）。「我发起的团」（邻里自提的签收核销）收进那一页的页底 -->
      <view class="sh-cell sh-row sh-row--between" @tap="gotoMyGroups">
        <text class="txt-body cell__label">{{ $t("myGroups.title") }}</text>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="gotoGroups">
        <text class="txt-body cell__label">{{ $t("groups.title") }}</text>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="gotoVisited">
        <text class="txt-body cell__label">{{ $t("visited.title") }}</text>
      </view>
    </view>

    <!--
      开店入口（2026-09-28 拍板：小程序上也要能注册）。
      显不显示由**后端开关**决定（`merchant.apply.mp-visible`，随 bootstrap 下发）——
      做成开关是为了让「被微信判成平台型经营而驳回」这条风险可回滚：
      真驳回了运营在后台关一下就止血，不用重新发版重新提审。

      **从「邻里」那组里单拎出来做成一块。** 此前它是那一组的第五行，
      与「我的拼团」「我买过的商家」并列 —— 一个想开店的人在一串「我买过什么」里
      看不见它。文案也从「商家入驻」换成「我也想开店」：前者是平台视角的流程名，
      后者是他心里那句话。
    -->
    <view v-if="merchantApplyVisible(config.features)" class="sh-card sh-row sh-row--between open-shop"
          @tap="applyMerchant">
      <text class="txt-title txt-primary">{{ $t("merchant.openShop") }}</text>
      <!--
        有申请时这里是审核状态（那是状态，要显示）；没申请时给一个进入指示。
        **不配副标题** —— 上面刚把整页的解释文案删干净，这里再写一句
        「不收入驻费与年费…」就是同一个毛病。标题自己说得清。
      -->
      <text class="txt-caption txt-primary">{{ applyStatus ? applyStatusText : "›" }}</text>
    </view>

    <!--
      商家运营：**只在并进 B 端分包的构建里出现**（见 gotoBizOps 说明）。
      店主在手机上随手看单 / 核销 / 上下架 / 售后；建品、盘点、报表等重活在商家版 App 里。
    -->
    <view v-if="withBiz && user.isLogin" class="sh-card sh-row sh-row--between open-shop" @tap="gotoBizOps">
      <text class="txt-title txt-primary">{{ $t("merchant.bizOps") }}</text>
      <text class="txt-caption txt-primary">›</text>
    </view>

    <!--
      提交入驻之后那一屏（2026-09-28 拍板的后半句）：**下一步在 App 里做**。
      不说这句的话，他提交完会在小程序里找「我的店铺」——
      而那里什么都没有，看起来像是没提交成功。
    -->
    <!--
      报过名的人点开店卡片看到的是这一屏：自己填过什么、审到哪一步、还能不能改。
      **不再是一张空表** —— 此前不管有没有单都开新建表单，他填完一遍才被后端拒。
    -->
    <sh-sheet
      :visible="applyViewVisible"
      :title="String($t('merchant.intentTitle'))"
      @close="applyViewVisible = false"
    >
      <view class="intent">
        <view class="intent__row sh-row sh-row--between">
          <text class="txt-caption intent__k">{{ $t("merchant.intentStatus") }}</text>
          <text class="txt-body txt-primary">{{ applyStatusText }}</text>
        </view>
        <!-- 驳回理由是他下一步动作的全部依据，不写就等于让他猜着改 -->
        <text v-if="applyRejected && applyStatus?.rejectReason" class="txt-body intent__reason">
          {{ applyStatus.rejectReason }}
        </text>
        <view v-for="f in intentFields" :key="f.k" class="intent__row sh-row sh-row--between">
          <text class="txt-caption intent__k">{{ f.k }}</text>
          <text class="txt-body sh-fill intent__v">{{ f.v }}</text>
        </view>
      </view>

      <view v-if="applyEditable" class="sh-btn intent__act" @tap="editApply">
        {{ $t("merchant.intentEdit") }}
      </view>
      <view v-else-if="applyRejected" class="sh-btn intent__act" @tap="resubmitApply">
        {{ $t("merchant.intentResubmit") }}
      </view>
      <!-- 审核中与已通过都改不了，但要说清为什么，否则他会反复找那个按钮 -->
      <text v-else class="txt-caption intent__locked">{{ $t("merchant.intentLocked") }}</text>

      <biz-app-download :link="appLink" :other-link="otherAppLink" :ios="isIos" :version="config.merchantApp.androidVersion" />
    </sh-sheet>

    <sh-sheet :visible="appDownloadVisible" :title="String($t('merchant.applyDoneTitle'))" @close="appDownloadVisible = false">
      <text class="txt-body block done__body">{{ $t("merchant.applyDoneBody") }}</text>
      <biz-app-download :link="appLink" :other-link="otherAppLink" :ios="isIos" :version="config.merchantApp.androidVersion" primary />
      <view class="sh-btn sh-btn--muted done__btn" @tap="appDownloadVisible = false">{{ $t("common.later") }}</view>
    </sh-sheet>

    <!-- 设置：与生意无关，放最后 -->
    <view class="sh-cells">
      <!--
        联系客服（TDD-C 端裂变与商家招募 §4.1）。

        <p>**优先走微信客服**（企业微信那款，`wx.openCustomerServiceChat`）——
        会话落在企业微信里，能分配、有存档。参数由冷启动的 bootstrap 下发，
        没配齐就退回小程序原生的客服会话（`open-type="contact"`）。
        这也是这一屏在小程序里唯一能做的对外联络 ——
        招商、入驻、商家申请那一类按类目红线不能出现（自营类目的包里有它会被判平台型经营
        而驳回），而「联系客服」是任何小程序都该有的。

        <p>⚠️ 它要在微信后台**配过客服人员**才有反应，否则点了什么都不发生。
        H5/App 上没有这个能力，那两端显示平台邮箱（那里本来就有入驻入口）。
      -->
      <view class="sh-cell sh-row sh-row--between contact">
        <text class="txt-body cell__label">{{ $t("me.contact") }}</text>
        <!-- 小程序上原生客服按钮自己会说话，不再加一句解释；H5 上显示邮箱 —— 那是信息不是解释 -->
        <text v-if="!nativeContact" class="txt-caption cell__value">{{ PLATFORM_EMAIL }}</text>
        <!-- 配齐了走微信客服（会话落在企业微信里）；没配齐退回小程序原生客服会话 -->
        <view v-else-if="wxKf" class="contact__btn" @tap="openWxKf"></view>
        <button v-else class="contact__btn" open-type="contact"></button>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="themeVisible = true">
        <text class="txt-body cell__label">{{ $t("me.appearance") }}</text>
      </view>
      <!--
        帮助中心这一行带**构建版本号**。它不是给用户看的功能说明，
        是为了回答一个联调时反复出现的问题：**我手上这份是不是刚传的那一版**。
        没有它的时候，「改了没生效」与「装的是旧包」在屏幕上长得一模一样，
        而两者的排查方向完全相反。
        版本串形如 `0.1.1 · 0904-1955`，后半段是构建时刻 —— 见 vite.config.mts。
      -->
      <view class="sh-cell sh-row sh-row--between">
        <text class="txt-body cell__label">{{ $t("me.help") }}</text>
      </view>
      <view class="sh-cell sh-row sh-row--between">
        <text class="txt-body cell__label">{{ $t("me.version") }}</text>
        <text class="txt-caption cell__value">{{ buildVersion }}</text>
      </view>
      <!-- 此前**整个 c-app 没有退出登录入口** —— store 里的 logout() 是死代码。
           没有入口意味着共用设备上无法结束会话，而令牌在服务端一直有效 -->
      <view v-if="user.isLogin" class="sh-cell sh-row sh-row--between" @tap="onLogout">
        <text class="txt-body cell__label">{{ $t("me.logout") }}</text>
      </view>
    </view>

    <!--
      注销单独一块、离「退出登录」远一点。两者一字之差、后果天差地别：
      退出登录再登回来就是；注销之后同一个微信进来是一个全新账号。
      微信要求有这个入口（上架审核会查），但不该让它看起来像个常用操作。
    -->
    <view v-if="user.isLogin" class="danger" @tap="onDeregister">
      <text class="txt-sub">{{ $t("me.deregister") }}</text>
    </view>

    <sh-theme-sheet :visible="themeVisible" @close="themeVisible = false"></sh-theme-sheet>

    <!-- 商家入驻申请 -->
    <sh-sheet
      :visible="merchantVisible"
      :title="String($t('merchant.apply'))"
      :hint="String($t('merchant.applyFormHint'))"
      @close="merchantVisible = false"
    >
        <!--
          **只留四项**（2026-09-28）：店铺类型、店铺名称、经营范围、手机号。
          此前是八项 —— 主体类型（个人/个体户/企业）、联系人、简介、推荐人都在这儿，
          一个还没决定要不要开店的人，第一眼看到的是一张要填八格的表。
          那四项后端全都不是必填：主体与简介由运营在审核时核对，联系人用不上
          （有手机号就够），推荐人改走官网与企微（端内出现奖励文案会被判平台型经营）。
        -->
        <!--
          店铺类型用**选一项**，不用一排平铺的分段。七个类目平铺到一行里，
          每一格只剩两字宽 ——「居民生活服务」被折成六行竖排的单字，
          一眼读不出哪一格是哪一类，而这恰恰是这张表的第一个问题。
          行业数量还会随运营配置长，平铺的那条路越往后越窄。
        -->
        <view class="field__input sh-row sh-row--between types" @tap="pickIndustry">
          <text class="txt-body" :class="mForm.industry ? '' : 'txt-quiet'">
            {{ industryLabel || $t("merchant.pickIndustry") }}
          </text>
          <!-- 可点开的指示。用 `›` 不用 sh-icon：图标表里没有「向右」这一个
               （name 传个不存在的值不报错，只是什么都不画），而页面上
               「我也想开店」那张卡用的也是这个字符，两处是同一个意思 -->
          <text class="txt-caption txt-quiet">›</text>
        </view>
        <!--
          **未开放不等于不能报名**：拦下来就等于又拿准入的尺子量意向。
          这一句是告知，不是错误 —— 所以不用错误色，也不禁用提交按钮。
        -->
        <view v-if="industryNotOpen" class="apply__hint">{{ $t("merchant.industryNotOpen") }}</view>
        <input
          v-if="industryIsOther"
          maxlength="24"
          v-model="mForm.industryNote"
          class="field__input"
          :placeholder="$t('merchant.industryNote')"
        />

        <input maxlength="64" v-model="mForm.name" class="field__input" :placeholder="$t('merchant.shopName')" />
        <input maxlength="64" v-model="mForm.category" class="field__input" :placeholder="$t('merchant.category')" />
        <input
          v-model="mForm.contactPhone"
          class="field__input"
          type="number"
          maxlength="11"
          :placeholder="$t('merchant.phone')"
        />

        <view class="sh-btn sheet__save" :class="{ 'is-disabled': !mValid }" @tap="submitMerchant">
          {{ $t("merchant.submitApply") }}
        </view>

        <!--
          **提交前就说清下一步在哪**：经营动作（上架、改价、接单、发货、看账）
          全在商家版 App 里，小程序这一侧只承接报名。提交完那一屏也说了同一句，
          但那时人已经填完了 —— 有人是先想知道「开完店我在哪儿干活」才决定填不填的。
          电话是给填表当中卡住的人的，`makePhoneCall` 三端都走得通。
        -->
        <view class="apply-foot">
          <text class="txt-caption apply-foot__tip">{{ $t("merchant.appTip") }}</text>
          <text class="sh-link apply-foot__call" @tap="callSales">{{ $t("merchant.callSales", { p: SALES_PHONE }) }}</text>
        </view>
    </sh-sheet>
    <!--
      **必须留在 sh-scaffold 里面。** 这套 `--sh-*` 变量声明在 `:root, .sh-root` 上，
      而**小程序里没有 `:root`** —— 根节点叫 `page`，那条选择器一个节点都不匹配，
      全靠 scaffold 根节点上的 `.sh-root`。挂到 scaffold 外面就一个变量都继承不到：
      遮罩和卡片背景 `var(--sh-scrim)` / `var(--sh-surface)` 双双落空变透明，
      弹层文字直接浮在商品列表上，**看起来像页面串了行，而不像弹窗坏了**。
      H5 上不会露：浏览器里 `:root` 是匹配的。见 shared/tests/scaffold-scope.test.ts
    -->
    <phone-gate :visible="phoneGate" @done="phoneGate = false" @close="phoneGate = false" />
    <phone-gate :visible="phoneRequired.visible.value" :suggest="phoneRequired.suggest.value"
    @done="onPhoneBound" @close="onPhoneGateClose" />
</sh-scaffold>
</template>

<style scoped>

/* 选一项的那一行：和下面几个输入框同一个形状，只是右端多一个指示 */
.types {
  margin-top: 24rpx;
}
/*
  「这一类还没开放」。**用 --sh-sub 不用 --sh-danger**：它是告知而不是错误 ——
  报名照样收（拦下来就等于又拿准入的尺子量意向），用错误色会让人以为自己填错了。
*/
.apply__hint {
  display: block;
  margin-top: 16rpx;
  color: var(--sh-sub);
}
/* 查看态：标签定宽左列、取值右列，横着读完一条（与门店卡的 facts 同一个形状） */
.intent__row {
  margin-top: 16rpx;
}
.intent__k {
  width: 140rpx;
  flex-shrink: 0;
}
.intent__v {
  text-align: end;
}
/* 驳回理由自己占一段：它是下一步动作的依据，挤在行里会被当成一个普通字段 */
.intent__reason {
  display: block;
  margin-top: 16rpx;
  color: var(--sh-danger);
}
.intent__act {
  margin-top: 28rpx;
}
.intent__locked {
  display: block;
  margin-top: 28rpx;
  text-align: center;
}

/* 报名已提交那一屏：说明与两颗按钮之间要有间距 —— `.sh-btn` 自己是 display:block
   但不带外边距，而模板上那个 `block` 只是 UnoCSS 的 display 工具类，什么间距都不给。
   此前三件东西是贴死在一起的。 */
.done__body {
  margin-bottom: 16rpx;
}
.done__btn {
  margin-top: 16rpx;
}

/* 提交按钮下面那两行：一句说下一步在哪，一行电话。居中、弱化，不跟按钮抢。
   **不用 flex**：两行文字不需要一个横排容器，而 `display:flex + align-items:center + gap`
   这个形状会被当成自造的「横排行」（库里那件是 .sh-row，语义对不上）。
   颜色也不自写 —— 电话走 .sh-link，那是「可点的次要动作」现成的件。 */
.apply-foot {
  margin-top: 24rpx;
  text-align: center;
}
.apply-foot__tip,
.apply-foot__call {
  display: block;
}
.apply-foot__call {
  margin-top: 8rpx;
}
/* 与 address 逐字节相同的一份重写，现在都走 `.field__input`，只留纵向间距 */
.field__input {
  margin-top: 16rpx;
}
.sheet__save {
  margin-top: 32rpx;
}
.head {
  gap: 28rpx;
}
.head__avatar {
  width: 104rpx;
  height: 104rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  text-align: center;
  line-height: 104rpx;
  font-size: 52rpx;
  flex-shrink: 0;
}

/*
 * 真头像那一支：盖掉上面那三条给文字用的属性。
 * 不盖的话 line-height 会把图往下推，而 <image> 的默认尺寸会盖过 width/height。
 */
.head__avatar--img {
  line-height: 0;
  font-size: 0;
  background: transparent;
}

.head__name {
  display: block;
}
.head__sub {
  display: block;
  margin-top: 8rpx;
}
.danger {
  margin: 48rpx 0 24rpx;
  padding: 24rpx;
  text-align: center;
}

/* 开店入口：单独一块、主色标题 —— 它此前混在「我买过什么」那一串里，想开店的人看不见。
   标题与右侧状态的颜色走库件 .txt-primary（base.css 里它晚于 .txt-title / .txt-caption
   定义，所以盖得住它们的 color），页面这边只留底色 */
.open-shop {
  background: var(--sh-primary-tint);
}
.cell__label {
  flex-shrink: 0;
}
.cell__value {
  text-align: end;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 客服：原生按钮盖在整行上，自己不占视觉 */
.contact {
  position: relative;
}
.contact__btn {
  position: absolute;
  inset: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  border: none;
  opacity: 0;
}
</style>
