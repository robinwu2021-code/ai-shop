// 单一 API 契约（组合根）。按域切片在 lib/api/contracts/*.ts，本文件只做组合与再导出。
// mock 与真实后端各实现一份（lib/api/mock.ts / http.ts），页面只依赖此接口，
// 切换靠 lib/api/index.ts 一处开关（消除散落的 if(USE_MOCK)）。
//
// 新增 API 该放哪：找到对应域的 contracts/<域>.ts 加方法签名，
// 再到 mocks/<域>.ts 与 https/<域>.ts 各补一处实现。三个组合根不需要改。
// ⚠️ 方法名禁止以 delete 开头（contract.test.ts 断言），软删除一律 archive*/unarchive*。
import type { DashboardApi } from "./contracts/dashboard";
import type { MerchantApi } from "./contracts/merchant";
import type { OrderApi } from "./contracts/order";
import type { PaymentApi } from "./contracts/payment";
import type { CommunityApi } from "./contracts/community";
import type { FulfillmentApi } from "./contracts/fulfillment";
import type { StoreApi } from "./contracts/store";
import type { MarketingApi } from "./contracts/marketing";
import type { ReviewApi } from "./contracts/review";
import type { AfterSaleApi } from "./contracts/aftersale";
import type { GroupApi } from "./contracts/group";
import type { ProductApi } from "./contracts/product";
import type { FinanceApi } from "./contracts/finance";
import type { IamApi } from "./contracts/iam";
import type { GrowthApi } from "./contracts/growth";
import type { RiskApi } from "./contracts/risk";
import type { MessageApi } from "./contracts/message";
import type { ContentApi } from "./contracts/content";
import type { SystemApi } from "./contracts/system";
import type { MemberApi } from "./contracts/member";

// 查询参数集中在 query.ts；此处再导出，保持 `@/lib/api` 的对外导出面不变。
export * from "./query";
export type { LoginResp } from "./contracts/dashboard";
export type {
  DashboardApi, MerchantApi, OrderApi, PaymentApi, CommunityApi, FulfillmentApi, StoreApi,
  MarketingApi, ReviewApi, AfterSaleApi, GroupApi, ProductApi, FinanceApi, IamApi, GrowthApi, RiskApi, MessageApi, ContentApi, SystemApi,
  MemberApi,
};

export interface Api
  extends DashboardApi, MerchantApi, OrderApi, PaymentApi, CommunityApi, FulfillmentApi, StoreApi,
    MarketingApi, ReviewApi, AfterSaleApi, GroupApi, ProductApi, FinanceApi, IamApi, GrowthApi, RiskApi,
    MessageApi, ContentApi, SystemApi, MemberApi {}
