import { http, idempotencyKey } from '@shared/net/http-client';
import { PRINT_ENDPOINTS, type PrintEndpointId } from './print-endpoints';
export interface PrintPage<T> { records:T[]; total:number; page:number; size:number }
export interface FrozenJson { canonicalJson:string; sha256:string }
export interface PrintOperation { operationNo:string; type:string; status:'PENDING'|'UNKNOWN'|'SUCCEEDED'|'FAILED'; targetType:string; targetNo:string; result:FrozenJson|null; error?:{key:string}|null }
export interface Printer { printerNo:string;storeNo:string;name:string;connectionType:string;adapterCode:string;paperWidthMm:number;status:string;healthStatus:string;revision:number }
export interface Agent { agentNo:string;name:string;status:string;revision:number }
export interface DiscoveredPrinter {physicalRegistrationRef:string;queueName:string}
export interface AgentDetail {agent:Agent;printerNos:string[];discoveredPrinters:DiscoveredPrinter[]}
export interface PrinterDetail {printer:Printer;connectionRevision:number;runtimeRevision:number;agentNo:string|null;activeJobNo:string|null;blockedReason:string|null}
export interface PrintJob {jobNo:string;requestNo:string;batchNo:string;scene:string;printerNo:string;status:string;jobKind:string;rootJobNo:string;revision:number;resolutionStatus:string;allowedActions?:string[];lastErrorCode?:string|null}
export interface FactRef {tenantNo:string;entityNo:string;storeNo:string;sourceType:string;factNo:string;bizRevision:string;scene:string;snapshotHash:string}
export interface PrintSummary {generationState:string;expectedCount:number;confirmedCount:number;replacementResolvedCount:number;unknownCount:number;failedCount:number;sourceFacts:FactRef[]}
export interface PrintRequest {requestNo:string;orderNo:string|null;subOrderNo:string|null;scene:string;status:string;blockCode:string|null;revision:number}
export interface Scene {scene:string;title:string;sourceAvailable:boolean;variants:string[];sourceTypes:string[];triggers:string[]}
export interface Simulation {simulationNo:string;scene:string;status:string;fixtureCode:string;expectedResults:FrozenJson;actualResults:FrozenJson;eventJournal:FrozenJson;revision:number}
export interface ExportView {exportNo:string;sourceType:string;sourceNo:string;status:string;fileSize:number;pageCount:number;revision:number;expiresAt:string}
export interface PrintTemplate {templateNo:string;scene:string;variantCode:string;name:string;currentVersionNo:string|null;revision:number;scopeKey:string}
export interface Route {routeNo:string;scene:string;currentVersionNo:string|null;revision:number}
export interface ScenePolicy {policyNo:string;scene:string;enabled:boolean;revision:number;policy:FrozenJson}
export interface TemplateBlock {type:string;binding?:string;literal?:string;label?:string;omitIfEmpty?:boolean;fields?:string[];[key:string]:unknown}
export interface TemplateContent {blocks:TemplateBlock[];layout:Record<string,unknown>}
export interface Incident {incidentNo:string;incidentType:string;targetNo:string;status:string;severity:string;revision:number}
export const SIMULATION_FIXTURES=['SUCCESS','NOT_ACCEPTED','OFFLINE','PAPER_OUT','ACCEPTED_NO_RESULT','TIMEOUT_UNKNOWN','DUPLICATE_RECEIPT','LATE_RECEIPT','PARTIAL_COPIES'] as const;
export function decodeFrozen<T>(value:FrozenJson):T {return JSON.parse(value.canonicalJson) as T;}
export function operationResult<T>(op:PrintOperation):T {if(op.status!=='SUCCEEDED'||!op.result)throw new Error('PRINT_OPERATION_NOT_FINISHED');return decodeFrozen<T>(op.result);}
export function printPath(id:PrintEndpointId,variables:Record<string,string>={}):string {let path:string=PRINT_ENDPOINTS[id].path;return path.replace(/\{([A-Za-z]+)\}/g,(_,name:string)=>{const value=variables[name];if(!value)throw new Error('PRINT_PATH_PARAMETER_REQUIRED');return encodeURIComponent(value);});}
export async function callPrint<T>(id:PrintEndpointId,data?:object,variables:Record<string,string>={},key?:string):Promise<T>{const endpoint=PRINT_ENDPOINTS[id];const path=printPath(id,variables);if(endpoint.method==='GET')return http.get<T>(path,data);const headers=key?{'Idempotency-Key':key}:undefined;return endpoint.method==='PUT'?http.put<T>(path,data,headers):http.post<T>(path,data,headers);}
/** Keep one key per user intent so a timeout retry cannot create another reprint/export. */
export class PrintCommands {
 private readonly pending=new Map<string,string>();
 async run(id:PrintEndpointId,data:object,variables:Record<string,string>={}):Promise<PrintOperation>{const intent=JSON.stringify([id,variables,data]);let key=this.pending.get(intent);if(!key){key=idempotencyKey();this.pending.set(intent,key);}const result=await callPrint<PrintOperation>(id,data,variables,key);this.pending.delete(intent);return result;}
 clear(){this.pending.clear();}
}
