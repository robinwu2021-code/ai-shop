// 系统配置文案（矩阵 P-17.1）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {
  tabAppearance: "外观与语言",
  tabMarket: "市场与货币",
  tabFlags: "开关与灰度",

  toastSkinSaved: "已下发外观配置",
  toastTextsSaved: "规则文案已保存",
  toastMarketSaved: "已保存市场配置",
  toastFlagSaved: "已保存开关",

  colMarket: "市场",
  colCurrency: "货币",
  colTimezone: "时区",
  colRate: "汇率",
  colEnabled: "启用",
  colActions: "操作",
  baseCurrencyBadge: "基准货币",
  /** `{code}` 是货币代码 */
  ariaRate: "{code} 汇率",
  /** `{name}` 是市场/开关名 */
  ariaEnable: "{name} 启用",
  btnSaveRate: "保存汇率",

  colFlag: "开关",
  colKey: "键",
  colPercent: "灰度比例",
  colUpdatedAt: "上次修改",
  ariaPercent: "{name} 灰度比例",
  btnSavePercent: "保存比例",

  cardSkin: "C 端皮肤下发",
  skinReadOnlyWhat: "皮肤下发",
  skinNotice: "这里配的是 C 端的默认皮肤（C-TH-05）。用户手动选过皮肤后以本地偏好优先 —— 所以改这里只影响没自己选过的人。",
  skinSaveLabel: "下发",
  fieldDefaultSkin: "默认皮肤",
  defaultSkinHint:
    "这里只列可下发给 C 端的皮肤（与 packages/shared 的 SKINS 同名同色，改一处要改两处）。运营端专有的「商务蓝」不在其中 —— C 端没有这套。",
  fieldFestivalSkin: "节日皮肤（可空）",
  festivalOff: "不启用",
  /** `{from}` / `{to}` 是时间 */
  festivalRange: "生效区间：{from} ~ {to}",

  cardTexts: "规则文案（C 端展示）",
  textsNotice: "这三条会原样出现在 C 端的退款页、取货页和生鲜商详页。留空的话用户看到的是空白，所以服务端不允许保存空文案。",
  textRefund: "退款规则",
  textPickup: "自提规则",
  textWeighDiff: "称重差价规则",

  marketNotice: "多市场分别定价（B6）在商品域配，这里只维护市场本身与汇率。",
  /** `{cur}` 是基准货币代码 */
  baseCurrencyNotice: "{cur} 是基准货币，汇率恒为 1 —— 改了整套价格换算的原点就没了，所以界面上直接锁死。",
  marketEmpty: "还没有配置市场。市场决定了 C 端按哪种货币与时区展示，至少要有一个。",

  flagsReadOnlyWhat: "开关与灰度",
  flagsReadOnlyNote: "不能开关功能或调整灰度比例",
  flagsNotice: "灰度比例 0–100。新功能建议先开小比例观察 —— 关掉开关比回滚代码快得多。",
  flagsEmpty: "还没有功能开关。开关用于灰度放量，没有它新功能只能全量上线。",
};

const en: typeof zh = {
  tabAppearance: "Appearance & language",
  tabMarket: "Markets & currency",
  tabFlags: "Flags & rollout",

  toastSkinSaved: "Appearance settings pushed",
  toastTextsSaved: "Policy copy saved",
  toastMarketSaved: "Market settings saved",
  toastFlagSaved: "Flag saved",

  colMarket: "Market",
  colCurrency: "Currency",
  colTimezone: "Time zone",
  colRate: "FX rate",
  colEnabled: "Enabled",
  colActions: "Actions",
  baseCurrencyBadge: "Base currency",
  ariaRate: "{code} FX rate",
  ariaEnable: "Enable {name}",
  btnSaveRate: "Save rate",

  colFlag: "Flag",
  colKey: "Key",
  colPercent: "Rollout %",
  colUpdatedAt: "Last changed",
  ariaPercent: "{name} rollout percentage",
  btnSavePercent: "Save percentage",

  cardSkin: "Push skin to C-end",
  skinReadOnlyWhat: "skin push",
  skinNotice:
    "This sets the default skin for the C-end app (C-TH-05). Once a user picks a skin themselves, their local choice wins — so changing this only affects people who never chose one.",
  skinSaveLabel: "Push",
  fieldDefaultSkin: "Default skin",
  defaultSkinHint:
    "Only skins that exist on the C-end are listed (same names and colours as SKINS in packages/shared — change one, change both). The ops-only “Business blue” is excluded: the C-end has no such skin.",
  fieldFestivalSkin: "Seasonal skin (optional)",
  festivalOff: "Off",
  festivalRange: "Active window: {from} ~ {to}",

  cardTexts: "Policy copy (shown on the C-end)",
  textsNotice:
    "These three appear verbatim on the C-end refund page, pickup page and fresh-produce detail page. Left blank the user sees nothing, so the server rejects empty copy.",
  textRefund: "Refund policy",
  textPickup: "Pickup policy",
  textWeighDiff: "Weight-difference policy",

  marketNotice: "Per-market pricing (B6) is configured in the catalog domain; this page only maintains markets and FX rates.",
  baseCurrencyNotice:
    "{cur} is the base currency and its rate is always 1 — change it and every price conversion loses its origin, so it is locked in the UI.",
  marketEmpty: "No markets configured yet. A market decides which currency and time zone the C-end shows, so there must be at least one.",

  flagsReadOnlyWhat: "flags & rollout",
  flagsReadOnlyNote: "cannot toggle features or change rollout percentages",
  flagsNotice: "Rollout is 0–100. Start a new feature small and watch it — flipping a flag off is far faster than rolling back code.",
  flagsEmpty: "No feature flags yet. Flags are how features roll out gradually; without one a new feature can only ship to everybody at once.",
};

export const SYSTEM_COPY: PageCopy<typeof zh> = { zh, en };
