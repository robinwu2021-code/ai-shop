import { expect, it } from "vitest";
import { printOperationResult } from "./print-operation-result";
import type { PrintOperation } from "./api/inspection-types";

it("does not show reconciliation A while lookup B is pending or returns 404", () => {
  const accepted: PrintOperation = { operationNo: "A", type: "OPS_PRINT_RECONCILE", status: "SUCCEEDED", targetType: "STORE", targetNo: "ST1", result: null, error: null, createdAt: "2026-10-28T00:00:00Z", finishedAt: null };
  expect(printOperationResult("A", undefined, accepted)).toBe(accepted);
  expect(printOperationResult("B", undefined, accepted)).toBeUndefined(); // B loading
  expect(printOperationResult("B", undefined, accepted)).toBeUndefined(); // B 404
  expect(printOperationResult("B", accepted, accepted)).toBeUndefined(); // A cache cannot answer B
  const lookedUp = { ...accepted, operationNo: "B" };
  expect(printOperationResult("B", lookedUp, accepted)).toBe(lookedUp);
});
