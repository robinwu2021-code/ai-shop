// Mock 实现（组合根）。按域切片在 lib/api/mocks/*.ts，全部走 lib/mock/db 内存数据 + 模拟延迟。
// 切片之间方法名不得重叠（后者会静默覆盖前者），由 contract.test.ts 断言。
import type { Api } from "./contract";
import { dashboardMock } from "./mocks/dashboard";
import { merchantMock } from "./mocks/merchant";
import { orderMock } from "./mocks/order";
import { paymentMock } from "./mocks/payment";
import { communityMock } from "./mocks/community";
import { fulfillmentMock } from "./mocks/fulfillment";
import { storeMock } from "./mocks/store";
import { marketingMock } from "./mocks/marketing";
import { memberMock } from "./mocks/member";
import { reviewMock } from "./mocks/review";
import { afterSaleMock } from "./mocks/aftersale";
import { groupMock } from "./mocks/group";
import { productMock } from "./mocks/product";
import { financeMock } from "./mocks/finance";
import { iamMock } from "./mocks/iam";
import { growthMock } from "./mocks/growth";
import { riskMock } from "./mocks/risk";
import { messageMock } from "./mocks/message";
import { contentMock } from "./mocks/content";
import { systemMock } from "./mocks/system";

/** 各域 mock 切片。contract.test.ts 用它逐片校验 key 集合、检测重复实现。 */
export const MOCK_SLICES = {
  dashboard: dashboardMock,
  merchant: merchantMock,
  order: orderMock,
  payment: paymentMock,
  community: communityMock,
  fulfillment: fulfillmentMock,
  store: storeMock,
  marketing: marketingMock,
  member: memberMock,
  review: reviewMock,
  aftersale: afterSaleMock,
  group: groupMock,
  product: productMock,
  finance: financeMock,
  iam: iamMock,
  growth: growthMock,
  risk: riskMock,
  message: messageMock,
  content: contentMock,
  system: systemMock,
} as const;

export const mockApi: Api = {
  ...dashboardMock, ...merchantMock, ...orderMock, ...paymentMock,
  ...communityMock, ...fulfillmentMock, ...storeMock,
  ...marketingMock, ...reviewMock, ...afterSaleMock, ...groupMock, ...productMock, ...financeMock, ...iamMock, ...growthMock, ...riskMock, ...messageMock, ...contentMock, ...systemMock, ...memberMock,
};
