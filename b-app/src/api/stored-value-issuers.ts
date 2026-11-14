import type {SvPage,SvPlan,SvProgress,SvBrandOption,SvWallet,SvRecordsQuery,SvRecord,SvConsumptionCreate,SvConsumptionPreview,SvServiceView,SvOperation} from './stored-value-types';
export interface IssuerRoute {
/** Payment merchant assigned to this collection route; null until configured. */
paymentMerchantNo:string|null;
/** Operating entity identifier; authorization remains server-derived. */
entityNo:string;
/** Store identifier scoping the requested operation or returned business record. */
storeNo:string|null;
/** Delivery or collection channel used by this capability or route. */
channel:string;
/** Business scene in which the route or activity is applicable. */
scene:string}
export interface IssuerConfiguration {
/** Human-readable name displayed for this business resource. */
name:string;
/** Operating entity responsible for settling the issuer's liabilities. */
settlementEntityNo:string;
/** Whether issuer collection is centralized or performed by individual stores. */
collectionMode:string;
/** Clearing policy used for liabilities under this issuer configuration. */
clearingMode:string;
/** Policy for allocating redemption debits across funded balance components. */
debitAllocationMode:string;
/** ISO currency code determining the interpretation of monetary amounts. */
currency:string;
/** IANA time zone used for this issuer's market and business dates. */
marketZone:string;
/** Collection routes assigned to this issuer configuration. */
routes:IssuerRoute[];
/** Previously observed resource revision; stale writes are rejected. */
expectedRevision:number}
export interface IssuerView extends Omit<IssuerConfiguration,'expectedRevision'> {
/** Stored-value issuer identifier. */
issuerNo:string;
/** Immutable issuer-configuration version used by subsequent operations. */
issuerVersionNo:string;
/** Whether the issuer scope is a store or a brand. */
scopeType:'STORE'|'BRAND';
/** Brand or store identifier selected by scopeType. */
scopeNo:string;
/** Current server-owned lifecycle state of this resource. */
status:string;
/** Current revision used for optimistic concurrency or snapshot traceability. */
revision:number}
export interface IssuerImpact {
/** Whether the server permits this operation after checking all constraints. */
allowed:boolean;
/** Number of existing accounts affected by the proposed issuer change. */
affectedAccountCount:number;
/** Whether pending operations prevent applying this issuer change. */
hasPending:boolean;
/** Server-provided reasons preventing the requested operation. */
blockers:string[]}
export interface StoreIssuerOption {
/** Stored-value issuer identifier. */
issuerNo:string;
/** Whether the issuer scope is a store or a brand. */
scopeType:'STORE'|'BRAND';
/** Brand or store identifier selected by scopeType. */
scopeNo:string;
/** Human-readable name displayed for this business resource. */
name:string;
/** ISO currency code determining the interpretation of monetary amounts. */
currency:string;
/** Current server-owned lifecycle state of this resource. */
status:string;
/** Issuer writing mode describing which stored-value commands are currently supported. */
writeMode:string;
/** Whether the account and selected issuer currently permit recharging. */
canRecharge:boolean;
/** Whether the selected issuer currently permits stored-value redemption. */
canRedeem:boolean;
/** Server-provided explanation of why this account or issuer cannot be used. */
unavailableReason:string|null}
export interface StoreIssuerOptions {
/** Store identifier scoping the requested operation or returned business record. */
storeNo:string;
/** Whether this store still uses the compatibility issuer arrangement. */
legacyMode:boolean;
/** Issuer options applicable to the current store. */
issuers:StoreIssuerOption[]}
export interface IssuerConfigureRequest {
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Versioned issuer configuration to validate or apply. */
configuration:IssuerConfiguration}
export interface IssuerAccessAck {
/** Stored-value issuer identifier. */
issuerNo:string;
/** Store identifier scoping the requested operation or returned business record. */
storeNo:string;
/** Store/issuer source revision acknowledged by the access refresh. */
sourceRevision:number;
/** Current server-owned lifecycle state of this resource. */
status:string}
export interface IssuerApi {
 mSvConsumptionCancel(orderNo:string,storeNo:string):Promise<SvOperation>;
 mSvConsumptionResolve(body:SvConsumptionCreate,key:string):Promise<SvConsumptionResolution>;
 mSvConsumptionPreview(body:SvConsumptionCreate,key:string):Promise<SvConsumptionPreview>;
 mSvConsumptionOrder(orderNo:string,storeNo:string):Promise<SvServiceView>;
 mSvConsume(body:{orderNo:string;orderRevision:number;customerRef:string;authorizationRef:null}):Promise<SvOperation>;

 mSvStoreIssuerConfiguration(no:string,managementNo:string):Promise<{
/** Current issuer configuration; null when none has been configured. */
issuer:IssuerView|null}>;
 mSvConfigureStoreIssuer(no:string,body:IssuerConfigureRequest,key:string):Promise<IssuerView>;
 mSvIssuer(type:'STORE'|'BRAND',no:string,managementNo:string):Promise<{
  /** Current issuer configuration; null when none has been configured. */
  issuer:IssuerView|null}>;
 mSvConfigureIssuer(type:'STORE'|'BRAND',no:string,body:IssuerConfigureRequest,key:string):Promise<IssuerView>;
 mSvIssuerImpact(no:string,body:IssuerConfigureRequest):Promise<IssuerImpact>;
 mSvIssuerVersion(no:string,body:IssuerConfigureRequest,key:string):Promise<IssuerView>;
 mSvIssuerEntities(managementNo:string,scopeType:'STORE'|'BRAND',scopeNo:string):Promise<Array<{
/** Operating entity identifier; authorization remains server-derived. */
entityNo:string;
/** Human-readable name displayed for this business resource. */
name:string;
/** Whether the operating entity has accepted this issuer arrangement. */
accepted:boolean;
/** Whether the current operator may accept this issuer arrangement. */
canAccept:boolean}>>;
 mSvWalletRecords(storeNo:string,customerRef:string,q:Omit<SvRecordsQuery,'storeNo'> & {issuerRef:string;accountRef:string}):Promise<SvPage<SvRecord>>;
 mSvWallets(storeNo:string,customerRef:string):Promise<SvWallet[]>;
 mSvBrandCapability():Promise<SvBrandCapability>;
 mSvBrandActivate(brandNo:string,body:{managementNo:string;expectedRevision:number},key:string):Promise<SvBrandActivation>;
 mSvStoreIssuers(storeNo:string):Promise<StoreIssuerOptions>;
 mSvRechargeOptions(query:{
/** Store identifier scoping the requested operation or returned business record. */
storeNo:string;
/** Server-issued selected-customer reference; expires and must be reselected when stale. */
customerRef:string;scopeType?:'STORE'|'BRAND';
/** Opaque continuation cursor from the preceding page; omit for the first page. */
cursor?:string;
/** Maximum records per page requested or returned by the server. */
size?:number}):Promise<{
/** Issuer choices and their current recharge/redemption availability. */
issuerOptions:StoreIssuerOptions;
/** Recharge plans available for the selected store and customer. */
plans:SvPage<SvPlan>;brandOptions?:SvPage<SvBrandOption>|null}>;
 mSvIssuerRoutes(no:string,managementNo:string):Promise<IssuerRoute[]>;
 mSvPrepareIssuer(storeNo:string,managementNo:string):Promise<IssuerView>;
 mSvRefreshIssuer(storeNo:string,managementNo:string):Promise<IssuerAccessAck>;
 mSvIssuerRecharge(rechargeNo:string,storeNo:string):Promise<SvProgress>;
 mSvIssuerCancel(rechargeNo:string,storeNo:string,body:{
  /** Previously observed resource revision; stale writes are rejected. */
  expectedRevision:number;
  /** Business reason recorded for the operation, rejection or manual recovery. */
  reason:string},key:string):Promise<SvProgress>;
}

export interface SvConsumptionResolution {
/** Whether the original idempotent consumption preview has been found. */
found:boolean;
/** Original stored-value service view; null when the operation has not produced a service. */
service:SvServiceView|null}
export interface SvBrandCapability {
/** Stored-value capability protocol version supported by the deployment. */
protocol:number;
/** Whether all prerequisites for brand stored-value execution are satisfied. */
ready:boolean;
/** Machine-readable reasons that prevent brand execution readiness. */
blockers:string[]}
export interface SvBrandActivation {
/** Identifier of the original brand financial activation operation. */
activationNo:string;
/** Current server-owned state of the brand financial activation. */
state:string}
