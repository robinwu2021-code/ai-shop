import type { Page } from "@/lib/types/common";
import type { PrintTarget, PrintFleetHealth, PrintIncident, PrintReconcileRequest, PrintOperation } from "../print-types";

export interface PrintApi {
  /** Scoped fleet health from PrintMonitorDtos. */
  printHealth(q: PrintTarget): Promise<PrintFleetHealth>;

  /** Scoped incident page; backend status codes pass through unchanged. */
  printIncidents(q: PrintTarget & {status?: string; page: number; size: number}): Promise<Page<PrintIncident>>;

  /** Audited maintenance; same key is retained for exact-intent error recovery. */
  reconcilePrint(q: PrintReconcileRequest, idempotencyKey: string): Promise<PrintOperation>;

  /** Read an operation only within the explicit target. */
  printOperation(operationNo: string, q: PrintTarget): Promise<PrintOperation>;
}
