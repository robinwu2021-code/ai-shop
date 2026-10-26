import type { Page } from "@/lib/types/common";
/** Explicit target references from Ops*Query; tenant identity supports 32 characters and entity/store V1 identities support 64. */
export interface PrintTarget {
 /** Tenant reference, up to 32 characters; validated by backend actor data scope. */ tenantNo: string;
 /** Merchant entity reference, up to 64 characters, not a numeric ID. */ entityNo: string;
 /** Store reference within entity and tenant, up to 64 characters. */ storeNo: string;
}
/** PrintMonitorDtos.GetHealthVO; counts are integer jobs/devices, timestamps are ISO-8601 Instant. */
export interface PrintHealth {
 /** Active printer count. */ activePrinters:number;
 /** Pending job count. */ pendingJobs:number;
 /** Unknown outcome job count; never implies completion. */ unknownJobs:number;
 /** Blocked request count. */ blockedRequests:number;
 /** Server snapshot Instant. */ asOf:string;
}
/** PrintMonitorDtos.FleetHealthVO; store order is backend-owned. */
export interface PrintFleetHealth { /** Scoped health snapshots. */ stores:PrintHealth[]; /** Fleet snapshot Instant. */ asOf:string }
/** PrintMonitorDtos.IncidentVO. */
export interface PrintIncident {
 /** Incident business reference. */ incidentNo:string;
 /** Owning store reference. */ storeNo:string;
 /** Backend incident classification. */ incidentType:string;
 /** Backend target classification. */ targetType:string;
 /** Target business reference. */ targetNo:string;
 /** Backend severity code. */ severity:string;
 /** Backend status code. */ status:string;
 /** Number of occurrences. */ occurrenceCount:number;
 /** First observed Instant. */ firstSeenAt:string;
 /** Last observed Instant. */ lastSeenAt:string;
 /** Optimistic revision, no UI write endpoint. */ revision:number;
}
/** OpsReconcileReq; from/to are Instants, not epoch milliseconds. */
export interface PrintReconcileRequest extends PrintTarget {
 /** Source kind owned by backend adapters. */ sourceKind:string;
 /** Inclusive scan start Instant. */ from:string;
 /** Scan end Instant. */ to:string;
 /** True previews; false executes scoped maintenance. */ dryRun:boolean;
 /** Required audit rationale. */ reason:string;
}
/** PrintOperation and FrozenJson preserve canonical JSON/hash without inventing execution counts. */
export interface PrintOperation {
 /** Operation business reference. */ operationNo:string;
 /** Backend operation type. */ type:string;
 /** PENDING/UNKNOWN are unfinished; SUCCEEDED describes operation only, never physical output. */ status:"PENDING"|"UNKNOWN"|"SUCCEEDED"|"FAILED";
 /** Backend target type. */ targetType:string;
 /** Backend target reference. */ targetNo:string;
 /** Frozen backend result, may be absent while pending. */ result:{canonicalJson:string;sha256:string}|null;
 /** Backend error metadata, not a retry authorization. */ error:{key:string;retryable:boolean;traceNo:string;conflicts:{canonicalJson:string;sha256:string}|null}|null;
 /** Creation Instant. */ createdAt:string;
 /** Completion Instant, absent when unfinished. */ finishedAt:string|null;
}
export type PrintIncidentPage = Page<PrintIncident>;
