import { describe, it, expect } from "vitest";
import { refundProgressKey } from "../src/strategies/order-phase1";
import type { AfterSale } from "../src/types";
const sale = (values = {}) => ({ status: "REFUNDING", refundExecution: { status: "SUCCEEDED", resourceStatus: "DONE", closureVersion: 2, closureStatus: "PENDING", cashStatus: "CONFIRMED", benefitsStatus: "PENDING", ...values } }) as AfterSale;
describe("versioned consumer closure progress", () => {
  it("cash success with pending benefits is not completion even if stock is done", () => {
    expect(refundProgressKey(sale())).toBe("afterSale.execution.benefitsPending");
  });
  it("zero cash does not claim a cash refund", () => {
    expect(refundProgressKey(sale({ cashStatus: "NOT_REQUIRED" }))).toBe("afterSale.execution.zeroBenefitsPending");
  });
  it("settled consumers do not wait for independent inventory recovery", () => {
    expect(refundProgressKey(sale({ closureStatus: "DONE", benefitsStatus: "SETTLED", resourceStatus: "PENDING" }))).toBe("afterSale.execution.benefitsDone");
  });
  it("zero cash does not conceal a failed or manual preparation", () => {
    expect(refundProgressKey(sale({ status: "FAILED", cashStatus: "NOT_REQUIRED" }))).toBe("afterSale.execution.failed");
    expect(refundProgressKey(sale({ status: "MANUAL_REQUIRED", cashStatus: "NOT_REQUIRED" }))).toBe("afterSale.execution.manual");
  });
  it("unknown cash is not concealed by benefit progress", () => {
    expect(refundProgressKey(sale({ status: "UNKNOWN", cashStatus: "UNKNOWN" }))).toBe("afterSale.execution.unknown");
  });
});
