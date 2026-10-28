import type { PrintOperation } from "./api/inspection-types";

/** A result belongs to the requested operation, even while its next query is pending or fails. */
export function printOperationResult(operationNo: string, queried?: PrintOperation, submitted?: PrintOperation) {
  return [queried, submitted].find((result) => result?.operationNo === operationNo);
}
