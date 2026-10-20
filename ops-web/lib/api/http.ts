// 真实后端实现（组合根）。按域切片在 lib/api/https/*.ts，端点前缀统一 /ops/**。
// 切片之间方法名不得重叠（后者会静默覆盖前者），由 contract.test.ts 断言。
import type { Api } from "./contract";
import { dashboardHttp } from "./https/dashboard";
import { merchantHttp } from "./https/merchant";
import { orderHttp } from "./https/order";
import { communityHttp } from "./https/community";
import { fulfillmentHttp } from "./https/fulfillment";
import { storeHttp } from "./https/store";
import { marketingHttp } from "./https/marketing";
import { memberHttp } from "./https/member";
import { reviewHttp } from "./https/review";
import { afterSaleHttp } from "./https/aftersale";
import { groupHttp } from "./https/group";
import { productHttp } from "./https/product";
import { inventoryHttp } from "./https/inventory";
import { jobHttp } from "./https/job";
import { financeHttp } from "./https/finance";
import { iamHttp } from "./https/iam";
import { growthHttp } from "./https/growth";
import { riskHttp } from "./https/risk";
import { messageHttp } from "./https/message";
import { paymentHttp } from "./https/payment";
import { contentHttp } from "./https/content";
import { systemHttp } from "./https/system";

/** 各域 http 切片。contract.test.ts 用它逐片校验 key 集合、检测重复实现。 */
export const HTTP_SLICES = {
  dashboard: dashboardHttp,
  merchant: merchantHttp,
  order: orderHttp,
  payment: paymentHttp,
  community: communityHttp,
  fulfillment: fulfillmentHttp,
  store: storeHttp,
  marketing: marketingHttp,
  member: memberHttp,
  review: reviewHttp,
  aftersale: afterSaleHttp,
  group: groupHttp,
  product: productHttp,
  inventory: inventoryHttp,
  job: jobHttp,
  finance: financeHttp,
  iam: iamHttp,
  growth: growthHttp,
  risk: riskHttp,
  message: messageHttp,
  content: contentHttp,
  system: systemHttp,
} as const;

export const httpApi: Api = {
  ...dashboardHttp, ...merchantHttp, ...orderHttp, ...paymentHttp,
  ...communityHttp, ...fulfillmentHttp, ...storeHttp,
  ...marketingHttp, ...reviewHttp, ...afterSaleHttp, ...groupHttp, ...productHttp, ...financeHttp, ...iamHttp, ...growthHttp, ...riskHttp, ...messageHttp, ...contentHttp, ...systemHttp, ...memberHttp, ...inventoryHttp, ...jobHttp,
};
