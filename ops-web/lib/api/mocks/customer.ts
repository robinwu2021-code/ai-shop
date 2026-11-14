import type { CustomerApi } from "../contracts/customer";
const unavailable = async (): Promise<never> => { throw new Error("CUSTOMER_REPORTING_REQUIRES_HTTP"); };
export const customerMock: CustomerApi = {
  customerRegistrations: unavailable, customerRegistrationStores: unavailable,
  customerBrands: unavailable, platformMembers: unavailable, platformMember: unavailable,
};
