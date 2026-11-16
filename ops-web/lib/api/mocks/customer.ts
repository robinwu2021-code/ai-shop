import { fail } from "@/lib/biz-error";
import type { CustomerApi } from "../contracts/customer";
const unavailable = async (): Promise<never> => { fail("客户报表需要连接真实后端，请切换 HTTP 模式后重试。", "Customer reporting requires the real backend. Switch to HTTP mode and try again."); };
export const customerMock: CustomerApi = {
  customerRegistrations: unavailable, customerRegistrationStores: unavailable,
  customerBrands: unavailable, platformMembers: unavailable, platformMember: unavailable,
};
