import { client } from "../http-client";
import type { CustomerApi } from "../contracts/customer";
export const customerHttp: CustomerApi = {
  customerRegistrations: q => client.get("/ops/customers/users", q),
  customerRegistrationStores: userNo => client.get(`/ops/customers/users/${encodeURIComponent(userNo)}/stores`),
  customerBrands: q => client.get("/ops/customers/chains", q),
  platformMembers: q => client.get("/ops/customers/members", q),
  platformMember: personNo => client.get(`/ops/customers/members/${encodeURIComponent(personNo)}`),
};
