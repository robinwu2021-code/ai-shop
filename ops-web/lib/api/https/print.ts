import type { PrintApi } from "../contracts/print";
import type { PrintFleetHealth, PrintIncidentPage, PrintOperation } from "../print-types";
import { client } from "../http-client";
export const printHttp:PrintApi={
 printHealth: (q) =>client.get<PrintFleetHealth>("/ops/print/health",q),
 printIncidents: (q) =>client.get<PrintIncidentPage>("/ops/print/incidents",q),
 reconcilePrint: (q, key) =>client.post<PrintOperation>("/ops/print/reconciliations",q,undefined,{"Idempotency-Key":key}),
 printOperation: (operationNo, q) =>client.get<PrintOperation>(`/ops/print/operations/${encodeURIComponent(operationNo)}`,q),
};
