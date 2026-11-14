import { http, idempotencyKey } from '@shared/net/http-client';
import { PRINT_ENDPOINTS, type PrintEndpointId } from './print-endpoints';
export interface PrintPage<T> {
  /** 当前页业务记录列表 */
  records:T[];
  /** 匹配条件的总记录数量 */
  total:number;
  /** 当前页码，从1开始 */
  page:number;
  /** 请求的每页记录数量 */
  size:number }
export interface FrozenJson { canonicalJson:string; sha256:string }
export interface PrintOperation { operationNo:string;
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type:string;
  /** 服务端业务状态编码 */
  status:'PENDING'|'UNKNOWN'|'SUCCEEDED'|'FAILED'; targetType:string; targetNo:string; result:FrozenJson|null; error?:{key:string}|null }
export interface Printer { printerNo:string;
  /** 门店编号 */
  storeNo:string;
  /** 业务对象显示名称 */
  name:string;connectionType:string;adapterCode:string;paperWidthMm:number;
  /** 服务端业务状态编码 */
  status:string;healthStatus:string;
  /** 当前记录版本号，用于并发控制 */
  revision:number }
export interface Agent { agentNo:string;
  /** 业务对象显示名称 */
  name:string;
  /** 服务端业务状态编码 */
  status:string;
  /** 当前记录版本号，用于并发控制 */
  revision:number }
export interface DiscoveredPrinter {physicalRegistrationRef:string;queueName:string}
export interface AgentDetail {agent:Agent;printerNos:string[];discoveredPrinters:DiscoveredPrinter[]}
export interface PrinterDetail {printer:Printer;connectionRevision:number;runtimeRevision:number;agentNo:string|null;activeJobNo:string|null;blockedReason:string|null}
export interface PrintJob {jobNo:string;requestNo:string;batchNo:string;
  /** 支付或业务场景编码 */
  scene:string;printerNo:string;
  /** 服务端业务状态编码 */
  status:string;jobKind:string;rootJobNo:string;
  /** 当前记录版本号，用于并发控制 */
  revision:number;resolutionStatus:string;allowedActions?:string[];lastErrorCode?:string|null}
export interface FactRef {tenantNo:string;
  /** 经营主体编号 */
  entityNo:string;
  /** 门店编号 */
  storeNo:string;sourceType:string;factNo:string;bizRevision:string;
  /** 支付或业务场景编码 */
  scene:string;snapshotHash:string}
export interface PrintSummary {generationState:string;expectedCount:number;confirmedCount:number;replacementResolvedCount:number;unknownCount:number;failedCount:number;sourceFacts:FactRef[]}
export interface PrintRequest {requestNo:string;
  /** 订单内部业务编号 */
  orderNo:string|null;
  /** 子订单内部业务编号 */
  subOrderNo:string|null;
  /** 支付或业务场景编码 */
  scene:string;
  /** 服务端业务状态编码 */
  status:string;blockCode:string|null;
  /** 当前记录版本号，用于并发控制 */
  revision:number}
export interface Scene {
  /** 支付或业务场景编码 */
  scene:string;
  /** 面向用户的业务标题 */
  title:string;sourceAvailable:boolean;variants:string[];sourceTypes:string[];triggers:string[]}
export interface Simulation {simulationNo:string;
  /** 支付或业务场景编码 */
  scene:string;
  /** 服务端业务状态编码 */
  status:string;fixtureCode:string;expectedResults:FrozenJson;actualResults:FrozenJson;eventJournal:FrozenJson;
  /** 当前记录版本号，用于并发控制 */
  revision:number}
export interface ExportView {exportNo:string;sourceType:string;sourceNo:string;
  /** 服务端业务状态编码 */
  status:string;fileSize:number;pageCount:number;
  /** 当前记录版本号，用于并发控制 */
  revision:number;
  /** 服务端引用失效时间 */
  expiresAt:string}
export interface PrintTemplate {templateNo:string;
  /** 支付或业务场景编码 */
  scene:string;variantCode:string;
  /** 业务对象显示名称 */
  name:string;currentVersionNo:string|null;
  /** 当前记录版本号，用于并发控制 */
  revision:number;scopeKey:string}
export interface Route {routeNo:string;
  /** 支付或业务场景编码 */
  scene:string;currentVersionNo:string|null;
  /** 当前记录版本号，用于并发控制 */
  revision:number}
export interface ScenePolicy {policyNo:string;
  /** 支付或业务场景编码 */
  scene:string;enabled:boolean;
  /** 当前记录版本号，用于并发控制 */
  revision:number;policy:FrozenJson}
export interface TemplateBlock {
  /** 业务类型编码或类型摘要，按当前接口定义解释 */
  type:string;binding?:string;literal?:string;label?:string;omitIfEmpty?:boolean;fields?:string[];[key:string]:unknown}
export interface TemplateContent {blocks:TemplateBlock[];layout:Record<string,unknown>}
export interface Incident {incidentNo:string;incidentType:string;targetNo:string;
  /** 服务端业务状态编码 */
  status:string;severity:string;
  /** 当前记录版本号，用于并发控制 */
  revision:number}
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
