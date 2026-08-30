// mock 数据集出口。**写操作必须真改这里的数组**（重开页面能读回），
// 伪实现（只返回不落库）会让页面在接后端前一直骗人（架构 §10.5）。
export * from "./helpers";
export { merchants, applies, authCodes, violations , admissionPolicies, depositTxns, payQuotas, storeModes, storeFulfillments, merchantStaff, qualifications, merchantPlans, planDefs } from "./merchant";
export { orders, orderInterventions } from "./order";
export { reconDiffs, closeRule } from "./payment";
export { shipments, freightTemplates, carriers } from "./logistics";
export { withdrawals, invoiceRequests, taxRule, invoiceTitle } from "./payout";
export { posts, rankings, questions } from "./ugc";
export { kpi, trend, funnel, merchantRanking } from "./dashboard";
export { communities, communityApplies, pickups, regions } from "./community";
export { batches, sorting, redeemStats, overdueRule } from "./fulfillment";
export { storeAudits, storeQrcodes, storeAcquisition, storeTemplates, stores } from "./store";
export { coupons, couponIssues, merchantCampaigns, platformSlots, contentSlots, memberCards } from "./marketing";
export { reviews, reviewAppeals, scoreConfig } from "./review";
export { afterSales, fastRefundRule } from "./aftersale";
export { groupCampaigns, demandOrders, quotes } from "./group";
export { categories, skus, goodsAudits, specTemplates, spuStds, topics, topicGoods } from "./product";
export { settlements, splitRecords, feeRules, payChannels, purchaseInvoices, buyerInvoiceRequests, settleBatches, merchantDebts, SETTLE_FREEZE_DAYS } from "./finance";
export { staffs, roleDefs, rolePoints, auditLogs } from "./iam";
export { attributionRule, attributionTraces, fissionCampaigns } from "./growth";
export { riskEvents, blacklists, riskRules } from "./risk";
export { msgTemplates, notifyQuota, tickets, faqs, defaultLang, inAppLogs } from "./message";
export { materials } from "./content";
export { appearance, markets, ruleTexts, featureFlags, industries, authCodeAdmins, serviceScopes } from "./system";
export { mediaStoreUsage, mediaReclaimable, mediaBatches } from "./media";
export { opsMembers, reachStats, opsPromoCoupons, opsPromoActivities } from "./member";
export { sceneChannels } from "./scene-channel";
