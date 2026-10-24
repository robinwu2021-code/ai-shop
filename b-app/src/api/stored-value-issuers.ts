import type {SvPage,SvPlan,SvProgress,SvBrandOption,SvWallet,SvRecordsQuery,SvRecord,SvConsumptionCreate,SvConsumptionPreview,SvServiceView,SvOperation} from './stored-value-types';
export interface IssuerRoute {paymentMerchantNo:string|null;entityNo:string;storeNo:string|null;channel:string;scene:string}
export interface IssuerConfiguration {name:string;settlementEntityNo:string;collectionMode:string;clearingMode:string;debitAllocationMode:string;currency:string;marketZone:string;routes:IssuerRoute[];expectedRevision:number}
export interface IssuerView extends Omit<IssuerConfiguration,'expectedRevision'> {issuerNo:string;issuerVersionNo:string;scopeType:'STORE'|'BRAND';scopeNo:string;status:string;revision:number}
export interface IssuerImpact {allowed:boolean;affectedAccountCount:number;hasPending:boolean;blockers:string[]}
export interface StoreIssuerOption {issuerNo:string;scopeType:'STORE'|'BRAND';scopeNo:string;name:string;currency:string;status:string;writeMode:string;canRecharge:boolean;canRedeem:boolean;unavailableReason:string|null}
export interface StoreIssuerOptions {storeNo:string;legacyMode:boolean;issuers:StoreIssuerOption[]}
export interface IssuerConfigureRequest {managementNo:string;configuration:IssuerConfiguration}
export interface IssuerAccessAck {issuerNo:string;storeNo:string;sourceRevision:number;status:string}
export interface IssuerApi {
 mSvConsumptionCancel(orderNo:string,storeNo:string):Promise<SvOperation>;
 mSvConsumptionResolve(body:SvConsumptionCreate,key:string):Promise<{found:boolean;service:SvServiceView|null}>;
 mSvConsumptionPreview(body:SvConsumptionCreate,key:string):Promise<SvConsumptionPreview>;
 mSvConsumptionOrder(orderNo:string,storeNo:string):Promise<SvServiceView>;
 mSvConsume(body:{orderNo:string;orderRevision:number;customerRef:string;authorizationRef:null}):Promise<SvOperation>;

 mSvStoreIssuerConfiguration(no:string,managementNo:string):Promise<{issuer:IssuerView|null}>;
 mSvConfigureStoreIssuer(no:string,body:IssuerConfigureRequest,key:string):Promise<IssuerView>;
 mSvIssuer(type:'STORE'|'BRAND',no:string,managementNo:string):Promise<{issuer:IssuerView|null}>;
 mSvConfigureIssuer(type:'STORE'|'BRAND',no:string,body:IssuerConfigureRequest,key:string):Promise<IssuerView>;
 mSvIssuerImpact(no:string,body:IssuerConfigureRequest):Promise<IssuerImpact>;
 mSvIssuerVersion(no:string,body:IssuerConfigureRequest,key:string):Promise<IssuerView>;
 mSvIssuerEntities(managementNo:string,scopeType:'STORE'|'BRAND',scopeNo:string):Promise<Array<{entityNo:string;name:string;accepted:boolean;canAccept:boolean}>>;
 mSvWalletRecords(storeNo:string,customerRef:string,q:Omit<SvRecordsQuery,'storeNo'> & {issuerRef:string;accountRef:string}):Promise<SvPage<SvRecord>>;
 mSvWallets(storeNo:string,customerRef:string):Promise<SvWallet[]>;
 mSvBrandCapability():Promise<{protocol:number;ready:boolean;blockers:string[]}>;
 mSvBrandActivate(brandNo:string,body:{managementNo:string;expectedRevision:number},key:string):Promise<{activationNo:string;state:string}>;
 mSvStoreIssuers(storeNo:string):Promise<StoreIssuerOptions>;
 mSvRechargeOptions(query:{storeNo:string;customerRef:string;scopeType?:'STORE'|'BRAND';cursor?:string;size?:number}):Promise<{issuerOptions:StoreIssuerOptions;plans:SvPage<SvPlan>;brandOptions?:SvPage<SvBrandOption>|null}>;
 mSvIssuerRoutes(no:string,managementNo:string):Promise<IssuerRoute[]>;
 mSvPrepareIssuer(storeNo:string,managementNo:string):Promise<IssuerView>;
 mSvRefreshIssuer(storeNo:string,managementNo:string):Promise<IssuerAccessAck>;
 mSvIssuerRecharge(rechargeNo:string,storeNo:string):Promise<SvProgress>;
 mSvIssuerCancel(rechargeNo:string,storeNo:string,body:{expectedRevision:number;reason:string},key:string):Promise<SvProgress>;
}
