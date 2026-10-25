import {afterEach,expect,it,vi} from "vitest";
import {printHttp} from "./api/https/print";
vi.mock("./auth",()=>({currentAuth:()=>null,scopeOf:()=>({tenantNo:"MAIN"}),useAuth:{getState:()=>({logout:vi.fn()})},refreshPerms:vi.fn()}));
afterEach(()=>vi.unstubAllGlobals());
it("transports explicit scope, ISO instants and Idempotency-Key without changing the request body",async()=>{
 const fetcher=vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({code:0,data:{operationNo:"OP"}})});vi.stubGlobal("fetch",fetcher);
 const target={tenantNo:"MAIN",entityNo:"ENTITY",storeNo:"STORE"};
 await printHttp.printHealth(target);
 expect(fetcher.mock.calls[0][0]).toContain("/ops/print/health?tenantNo=MAIN&entityNo=ENTITY&storeNo=STORE");
 const body={...target,sourceKind:"SUB_ORDER",from:"2026-10-24T00:00:00Z",to:"2026-10-25T00:00:00Z",dryRun:true,reason:"audit"};
 await printHttp.reconcilePrint(body,"intent-key");
 expect(fetcher.mock.calls[1][1].headers["Idempotency-Key"]).toBe("intent-key");
 expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual(body);
 await printHttp.printOperation("OP/1",target);
 expect(fetcher.mock.calls[2][0]).toContain("/ops/print/operations/OP%2F1?tenantNo=MAIN&entityNo=ENTITY&storeNo=STORE");
});
