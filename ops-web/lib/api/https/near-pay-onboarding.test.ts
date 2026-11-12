import {describe,expect,it,vi} from "vitest";
const http=vi.hoisted(()=>({get:vi.fn()}));
vi.mock("../http-client",()=>({client:http}));
import {merchantHttp} from "./merchant";
describe("NearPay onboarding read contract",()=>{
 it("passes entity pagination and keyword to the read endpoint unchanged",async()=>{const page={records:[],total:0,page:2,size:10};http.get.mockResolvedValue(page);const query={keyword:"Entity",page:2,size:10};expect(await merchantHttp.nearPayOnboardingBoard(query)).toBe(page);expect(http.get).toHaveBeenCalledWith("/ops/payment-onboardings",query);});
});
