// 导航标签的语言 overlay：以 lib/nav.ts 的中文 label 为源 key（nav.ts 保持中文 SSOT 不动），
// 需要哪个语言就在 OVERLAY 里叠加哪个：`{ "商家治理": { en: "Merchants" } }`。
//
// 为什么不把 nav.ts 的 label 改成 i18n key：导航是**产品结构**，改成 `nav.merchant.audit`
// 之类的 key 后，看 nav.ts 就再也看不出菜单长什么样，对照需求矩阵逐行核对会变得极难。
//
// ⚠️ 覆盖范围仅到**导航与框架**。页面正文（表头、说明条、空态文案）仍是中文硬编码 ——
// 这是 §九 M7「运营界面仅中文」那条决定的遗留，切到 EN 时正文不会跟着变。
// `nav.test.ts` 会断言这张表覆盖 nav.ts 的全部 label，漏一条就红。
import type { Locale } from "@/lib/stores/locale";

/** label → { locale: 译文 }。zh 不入表（源串即中文）。 */
const OVERLAY: Record<string, Record<string, string>> = {
  经营看板: { en: "Dashboard" },

  商家治理: { en: "Merchants" },
  准入与保证金: { en: "Admission & deposit" },
  无照自营风险: { en: "Unlicensed self-operated risk" },
  资质档案: { en: "Qualification records" },
  积分资金看板: { en: "Points fund overview" },
  入驻审核: { en: "Onboarding review" },
  商家档案: { en: "Merchant profiles" },
  门店档案: { en: "Store Directory" },
  类目授权: { en: "Category permits" },
  认证标管理: { en: "Verification badges" },
  信用档案: { en: "Credit records" },
  违规处置与封禁: { en: "Penalties & suspensions" },

  门店主页: { en: "Storefronts" },
  店招公告审核: { en: "Banner & notice review" },
  主页模板配置: { en: "Storefront templates" },
  店铺码生成导出: { en: "Store codes" },
  获客效果看板: { en: "Acquisition dashboard" },

  商品与类目: { en: "Catalog" },
  商品审核队列: { en: "Product review queue" },
  平台类目树: { en: "Category tree" },
  商品池与审核: { en: "Product pool & review" },
  预售额度与超卖: { en: "Presale quota & oversell" },
  规格模板维护: { en: "Spec templates" },
  通用规格: { en: "Universal specs" },
  专用规格: { en: "Dedicated specs" },
  "类目 × 规格": { en: "Categories × specs" },
  标准品库: { en: "Standard products" },
  主题分类: { en: "Topics" },
  // group 名也要译：漏一条，切到 EN 时分组标题夹一行中文
  标准品: { en: "Standard products" },
  陈列: { en: "Merchandising" },

  交易订单: { en: "Orders" },
  订单检索: { en: "Order search" },
  异常单处理: { en: "Exception orders" },
  "代客下单/取消": { en: "Order on behalf" },
  支付流水核对: { en: "Payment reconciliation" },
  掉单补偿: { en: "Dropped-order recovery" },
  关单策略配置: { en: "Auto-close rules" },

  履约调度: { en: "Fulfillment" },
  到货批次与配车: { en: "Batches & dispatch" },
  按自提点汇总分拣: { en: "Sorting by pickup point" },
  核销监控与逾期: { en: "Redemption & overdue" },
  逾期规则配置: { en: "Overdue rules" },
  快递与轨迹: { en: "Courier & tracking" },
  运费模板与超区: { en: "Shipping templates" },
  第三方运力配置: { en: "Carrier settings" },

  售后治理: { en: "After-sales" },
  售后工单池: { en: "After-sales tickets" },
  平台介入裁决: { en: "Platform adjudication" },
  极速退阈值配置: { en: "Instant-refund rules" },
  退款回退分账: { en: "Refund split reversal" },

  会员与人档: { en: "Members & persons" },
  会员名单: { en: "Member list" },
  人档: { en: "Person records" },
  触达健康度: { en: "Messaging health" },
  券敞口: { en: "Coupon exposure" },
  活动敞口: { en: "Activity exposure" },
  敞口: { en: "Exposure" },
  营销活动: { en: "Marketing" },
  券模板: { en: "Coupon templates" },
  发放记录: { en: "Issue records" },
  活动: { en: "Campaigns" },
  "首页楼层与 Banner": { en: "Home floors & banners" },
  会员卡与权益: { en: "Membership & benefits" },

  团购与求团: { en: "Group buying" },
  商家团: { en: "Merchant groups" },
  需求单池与指派: { en: "Demand pool" },
  改价留痕与毁约: { en: "Price changes & breaches" },

  增长与归因: { en: "Growth" },
  归因规则: { en: "Attribution rules" },
  归因链路审计: { en: "Attribution traces" },
  邀请有礼配置: { en: "Referral rewards" },

  结算与资金: { en: "Settlement" },
  结算单与分账: { en: "Settlements & splits" },
  分账明细: { en: "Split details" },
  分档费率与服务费: { en: "Rates & service fees" },
  提现审批: { en: "Withdrawal approval" },
  发票与个税: { en: "Invoices & tax" },

  评价治理: { en: "Reviews" },
  评价审核: { en: "Review moderation" },
  恶意差评申诉裁决: { en: "Review appeals" },
  评分算法参数: { en: "Rating parameters" },

  消息与客服: { en: "Messaging & support" },
  通道总览: { en: "Channel overview" },
  短信: { en: "SMS" },
  邮件: { en: "Email" },
  微信订阅消息: { en: "WeChat subscribe" },
  "App 推送": { en: "App push" },
  站内信模板: { en: "In-app templates" },
  发送记录: { en: "Send log" },
  营销广播: { en: "Marketing broadcast" },
  客服工单与代客留痕: { en: "Support tickets" },
  帮助中心维护: { en: "Help center" },

  社区与网点: { en: "Communities & points" },
  社区网格: { en: "Community grids" },
  商家提报: { en: "Merchant submissions" },
  区划维护: { en: "Region maintenance" },
  自提点: { en: "Pickup points" },
  临时点监控: { en: "Temporary points" },

  素材与内容: { en: "Content" },
  素材中心与分发: { en: "Material library" },
  种草内容审核: { en: "UGC moderation" },
  榜单与问答: { en: "Rankings & Q&A" },

  风控: { en: "Risk" },
  风险事件: { en: "Risk events" },
  黑名单与申诉: { en: "Blacklist & appeals" },
  拦截规则配置: { en: "Interception rules" },

  员工与权限: { en: "Staff & access" },
  员工账号与数据域: { en: "Accounts & data scope" },
  角色与权限: { en: "Roles & permissions" },
  菜单顺序: { en: "Menu order" },
  操作审计日志: { en: "Audit log" },

  系统配置: { en: "System" },
  经营范围: { en: "Business scope" },
  行业与小微白名单: { en: "Industries & micro-merchant allowlist" },
  经营授权码: { en: "Business authorisation codes" },
  经营范围开关: { en: "Business scope switches" },
  外观与规则文案: { en: "Appearance & policy copy" },
  "市场/货币/汇率": { en: "Markets & currency" },
  开关与灰度: { en: "Flags & rollout" },
  存储空间治理: { en: "Storage governance" },

  // ── L2 分组小标题（nav.ts 里的 leaf.group）──────────────────────────────
  入驻与资质: { en: "Onboarding & credentials" },
  信用与处置: { en: "Credit & penalties" },
  增值包: { en: "Plans" },
  增值包与额度: { en: "Plans & quota" },
  模板与合规: { en: "Templates & compliance" },
  获客: { en: "Acquisition" },
  类目: { en: "Categories" },
  商品: { en: "Products" },
  库存与预售: { en: "Stock & presale" },
  规格模板: { en: "Spec templates" },
  规格: { en: "Specs" },
  订单: { en: "Orders" },
  支付: { en: "Payments" },
  到货与分拣: { en: "Arrival & sorting" },
  核销: { en: "Redemption" },
  物流: { en: "Logistics" },
  处置: { en: "Adjudication" },
  规则: { en: "Rules" },
  优惠券: { en: "Coupons" },
  // 活动 / 商家团 两个分组名与同名叶子重了（2026-08-12 精简掉括号后缀之后）。
  // OVERLAY 是一张**扁平表**，同一个中文串只能有一条 —— 它们登记在上面的叶子区。
  内容位: { en: "Content slots" },
  会员: { en: "Membership" },
  求团撮合: { en: "Demand matching" },
  归因引擎: { en: "Attribution engine" },
  裂变活动: { en: "Referral campaigns" },
  分账结算: { en: "Settlement & splits" },
  费率: { en: "Rates" },
  提现与税: { en: "Withdrawals & tax" },
  审核: { en: "Moderation" },
  评分: { en: "Rating" },
  触达: { en: "Outreach" },
  客服: { en: "Support" },
  // 社区网格 / 自提点 既是 L3 叶子也是 L2 分组名，上面已登记，这里不重复
  素材: { en: "Materials" },
  内容: { en: "Content" },
  识别: { en: "Detection" },
  账号: { en: "Accounts" },
  审计: { en: "Audit" },
  外观与语言: { en: "Appearance & language" },
  运行配置: { en: "Runtime config" },
};

export function tNav(label: string, locale: Locale): string {
  if (locale === "zh") return label;
  return OVERLAY[label]?.[locale] ?? label;
}

/** 供 `nav.test.ts` 断言覆盖率用：overlay 里登记了哪些源串。 */
export const NAV_OVERLAY_KEYS = Object.keys(OVERLAY);
