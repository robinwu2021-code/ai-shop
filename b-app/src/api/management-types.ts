export interface ManagementPage<T> {items:T[];nextCursor:string|null}
export interface ManagementResource {canPrepareFinance?:boolean;canManageLegacy?:boolean;canConfigureBrand?:boolean;resourceType?:string;resourceNo?:string;name:string;status:string;revision:number;brandNo?:string;entityNo?:string;storeNo?:string;scopeRevision?:number}
export interface ManagementSpace extends ManagementResource {managementNo:string;actions:string[]}
export interface ManagementContext {actorKey:string;spaces:ManagementSpace[];canBootstrap:boolean;bootstrapSources:Array<{accountNo:string;entityNo:string;entityName?:string;stores:Array<{storeNo:string;name:string}>;paymentMerchants:Array<{payMerchantNo:string}>}>}
export interface Brand extends ManagementResource {storedValueStatus?:string;brandNo:string;managementNo:string;scopeRevision:number;logo?:string;storeCount?:number;createdAt?:string;updatedAt?:string;createdByLabel?:string;updatedByLabel?:string;actions?:string[]}
export interface ManagementGrant {actorLabel?:string;resourceName?:string;grantNo:string;managementNo:string;actorNo:string;resourceType:string;resourceNo:string;permissionCode:string;status:string;delegatable:boolean|number;revision:number}
export interface GrantCommand {actorNo:string;resourceType:string;resourceNo:string;permissionCode:string;delegatable?:boolean;validFrom?:string;validTo?:string;expectedRevision?:number;reactivate?:boolean}
export interface BrandDraft {managementNo:string;name:string;logo?:string|null;status?:'DRAFT'|'ACTIVE'}
export interface BrandEdit {name:string;logo?:string|null;expectedRevision:number}
export interface BrandImpact {allowed:boolean;canDecide?:boolean;blockers:string[];checkToken:string;storeVersion:number;bindingRevision:number;fromRevision:number|null;toRevision:number|null}
export interface BrandChangeSubmit {managementNo:string;toBrandNo:string|null;checkToken:string;expectedStoreRevision:number;expectedScopeRevision:number;expectedFromBrandRevision:number|null;expectedToBrandRevision:number|null;reason:string}
export interface BrandChange {financial?:boolean;changeNo:string;managementNo:string;storeNo:string;fromBrandNo?:string;toBrandNo?:string;status:string;revision:number;applied:boolean;blockers?:string[];actions?:string[];reason?:string}
export interface OperatingContext {storeNo:string;entityNo:string;name:string;status:string;revision:number;managementNo:string;brand:{brandNo:string;name:string;scopeRevision:number;storedValueStatus?:string}|null;actions:string[];pendingChange?:{changeNo:string;status:string;revision:number}|null}
export interface PaymentMerchant {payMerchantNo:string;entityNo:string;storeNo:string;payChannel:string;applyStatus:string;payMethods:string[]}
export interface ManagementAudit {action:string;actorKey:string;occurredAt:string}
export interface ManagementRecipient {actorNo:string;displayName:string}
export interface ManagementApi {
 mManagementRecipients(m:string,cursor?:string):Promise<ManagementPage<ManagementRecipient>>;
 mManagementContext():Promise<ManagementContext>;
 mCreateManagementSpace(body:{name:string;sourceAccountNo:string},key:string):Promise<ManagementSpace>;
 mManagementResources(m:string,query?:{resourceType?:string;view?:'EFFECTIVE'|'GRANTS';actorNo?:string;cursor?:string}):Promise<ManagementPage<ManagementGrant>>;
 mManagementGrant(m:string,body:GrantCommand):Promise<ManagementGrant>;
 mRevokeManagementGrant(m:string,g:string,revision:number,reason:string):Promise<ManagementGrant>;
 mManagementEntities(m:string,cursor?:string):Promise<ManagementPage<ManagementResource>>;
 mManagementStores(m:string,query?:{brandNo?:string;cursor?:string}):Promise<ManagementPage<ManagementResource>>;
 mPaymentMerchantCandidates(e:string,m:string,query?:{payChannel?:string;payScene?:string;storeNo?:string}):Promise<PaymentMerchant[]>;
 mBrands(m:string,query?:{status?:string;keyword?:string;cursor?:string}):Promise<ManagementPage<Brand>>;
 mCreateBrand(body:BrandDraft,key:string):Promise<Brand>;
 mBrand(b:string):Promise<Brand>;
 mEditBrand(b:string,body:BrandEdit,key:string):Promise<Brand>;
 mBrandStatus(b:string,body:{targetStatus:'ACTIVE'|'INACTIVE';expectedRevision:number;reason:string},key:string):Promise<Brand>;
 mBrandStores(b:string,cursor?:string):Promise<ManagementPage<ManagementResource>>;
 mBrandAudit(b:string,cursor?:string):Promise<ManagementPage<ManagementAudit>>;
 mStoreOperatingContext(s:string,m:string):Promise<OperatingContext>;
 mCheckBrandChange(s:string,body:{managementNo:string;toBrandNo:string|null}):Promise<BrandImpact>;
 mSubmitBrandChange(s:string,body:BrandChangeSubmit,key:string):Promise<BrandChange>;
 mBrandChange(c:string):Promise<BrandChange>;
 mDecideBrandChange(c:string,body:{decision:'APPROVE'|'REJECT';expectedRevision:number;reason:string},key:string):Promise<BrandChange>;
}
