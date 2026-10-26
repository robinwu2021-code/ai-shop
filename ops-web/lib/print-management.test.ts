import { describe, it, expect } from "vitest";
import { reconcileRequest, scopedTarget } from "./print-management";
import { printMock } from "./api/mocks/print";
import { useAuth } from "./auth";
describe("print management scope and intent", () => {
 it("requires all explicit references, trims and preserves 64 characters", () => {
  expect(() => scopedTarget({tenantNo:"MAIN",entityNo:"",storeNo:"S"})).toThrow();
  expect(scopedTarget({tenantNo:"MAIN",entityNo:"E".repeat(64),storeNo:" S "}).storeNo).toBe("S");
 });
 it("rejects missing reason and invalid or inverted instants", () => {
  const t={tenantNo:"MAIN",entityNo:"E",storeNo:"S"};
  expect(()=>reconcileRequest(t,"SUB_ORDER","bad","bad",true,"why")).toThrow();
  expect(()=>reconcileRequest(t,"SUB_ORDER","2026-01-02","2026-01-01",false,"why")).toThrow();
  expect(()=>reconcileRequest(t,"SUB_ORDER","2026-01-01","2026-01-02",true," ")).toThrow();
 });
 it("converts local date inputs to ISO instants and defaults dry run at page level", () => {
  const r=reconcileRequest({tenantNo:"MAIN",entityNo:"E",storeNo:"S"},"SUB_ORDER","2026-01-01T00:00Z","2026-01-02T00:00Z",true," audit ");
  expect(r.from).toBe("2026-01-01T00:00:00.000Z");expect(r.reason).toBe("audit");expect(r.dryRun).toBe(true);
 });
 it("mock refuses mutation rather than fabricating successful reconciliation", async () => {
  useAuth.setState({perms:["*"],tenantNo:"MAIN"});
  await expect(printMock.reconcilePrint({tenantNo:"MAIN",entityNo:"E",storeNo:"S",sourceKind:"SUB_ORDER",from:"2026-01-01T00:00:00Z",to:"2026-01-02T00:00:00Z",dryRun:true,reason:"audit"},"key")).rejects.toThrow();
 });
});

it("limits tenant references to 32 and entity/store references to 64",()=>{
 const t={tenantNo:"T".repeat(32),entityNo:"E".repeat(64),storeNo:"S".repeat(64)};
 expect(scopedTarget(t)).toEqual(t);
 expect(()=>scopedTarget({...t,tenantNo:"T".repeat(33)})).toThrow();
 expect(()=>scopedTarget({...t,entityNo:"E".repeat(65)})).toThrow();
 expect(()=>scopedTarget({...t,storeNo:"S".repeat(65)})).toThrow();
});
it("rejects unsupported cashier and implementation codes outside delivered sources",()=>{
 const t={tenantNo:"MAIN",entityNo:"E",storeNo:"S"};
 expect(()=>reconcileRequest(t,"CASHIER","2026-10-24","2026-10-25",true,"audit")).toThrow();
});
