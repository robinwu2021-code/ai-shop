export interface ManagementPage<T> {
  /** Records returned for the current continuation-cursor page. */
  items:T[];
  /** Opaque cursor for the next page; null when the result is exhausted. */
  nextCursor:string|null}
export interface ManagementResource {

  /** Whether the authenticated operator may prepare financial permissions for this resource. */
  canPrepareFinance?:boolean;
  /** Whether the operator retains management access to legacy resources. */
  canManageLegacy?:boolean;
  /** Whether the operator may configure the resource's brand binding. */
  canConfigureBrand?:boolean;
  /** Business resource category used to resolve resourceNo and enforce authorization. */
  resourceType?:string;
  /** Business identifier of the resource covered by the capability grant. */
  resourceNo?:string;
  /** Human-readable name displayed for this business resource. */
  name:string;
  /** Current server-owned lifecycle state of this resource. */
  status:string;
  /** Current revision used for optimistic concurrency or snapshot traceability. */
  revision:number;
  /** Brand identifier; null when the scope has no brand binding. */
  brandNo?:string;
  /** Operating entity identifier; authorization remains server-derived. */
  entityNo?:string;
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo?:string;
  /** Current revision of the resource's management-scope bindings. */
  scopeRevision?:number}
export interface ManagementSpace extends ManagementResource {
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Actions currently permitted by the server for this resource. */
  actions:string[]}
export interface ManagementContext {
  /** Authenticated operator identity recorded by the server. */
  actorKey:string;
  /** Management spaces accessible to the authenticated operator. */
  spaces:ManagementSpace[];
  /** Whether the operator may create a management space from an available authority source. */
  canBootstrap:boolean;
  /** Accounts the operator may use to establish a management space. */
  bootstrapSources:Array<{
  /** Account identifier used as the management-space authority source. */
  accountNo:string;
  /** Operating entity identifier; authorization remains server-derived. */
  entityNo:string;
  /** Display name of the operating entity providing the authority source. */
  entityName?:string;
  /** Stores accessible through this management authority source. */
  stores:Array<{
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo:string;
  /** Human-readable name displayed for this business resource. */
  name:string}>;
  /** Payment merchants available under the authority-source account. */
  paymentMerchants:Array<{
  /** Payment merchant identifier under the operating entity. */
  payMerchantNo:string}>}>}
export interface Brand extends ManagementResource {

  /** Server-owned stored-value activation state of this brand. */
  storedValueStatus?:string;
  /** Brand identifier; null when the scope has no brand binding. */
  brandNo:string;
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Current revision of the resource's management-scope bindings. */
  scopeRevision:number;
  /** Brand logo URL; null clears the configured logo. */
  logo?:string;
  /** Number of stores currently bound to this brand. */
  storeCount?:number;
  /** Resource creation time returned by the server. */
  createdAt?:string;
  /** Time the resource was last updated; null when it has never changed. */
  updatedAt?:string;
  /** Display label of the operator who created the resource. */
  createdByLabel?:string;
  /** Display label of the operator who last updated the resource. */
  updatedByLabel?:string;
  /** Actions currently permitted by the server for this resource. */
  actions?:string[]}
export interface ManagementGrant {
  /** Display label of the operator or grant recipient. */
  actorLabel?:string;
  /** Display name of the business resource covered by the grant. */
  resourceName?:string;
  /** Resource-capability grant identifier. */
  grantNo:string;
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Identity receiving the resource grant. */
  actorNo:string;
  /** Business resource category used to resolve resourceNo and enforce authorization. */
  resourceType:string;
  /** Business identifier of the resource covered by the capability grant. */
  resourceNo:string;
  /** Capability code granted for the specified business resource. */
  permissionCode:string;
  /** Current server-owned lifecycle state of this resource. */
  status:string;
  /** Whether the recipient may delegate this granted capability. */
  delegatable:boolean|number;
  /** Current revision used for optimistic concurrency or snapshot traceability. */
  revision:number}
export interface GrantCommand {
  /** Identity receiving the resource grant. */
  actorNo:string;
  /** Business resource category used to resolve resourceNo and enforce authorization. */
  resourceType:string;
  /** Business identifier of the resource covered by the capability grant. */
  resourceNo:string;
  /** Capability code granted for the specified business resource. */
  permissionCode:string;
  /** Whether the recipient may delegate this granted capability. */
  delegatable?:boolean;
  /** Start of the grant's validity window; omitted to use the server default. */
  validFrom?:string;
  /** End of the grant's validity window; omitted for an unrestricted end. */
  validTo?:string;
  /** Previously observed resource revision; stale writes are rejected. */
  expectedRevision?:number;
  /** Whether an existing revoked grant should be reactivated instead of duplicated. */
  reactivate?:boolean}
export interface BrandDraft {
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Human-readable name displayed for this business resource. */
  name:string;
  /** Brand logo URL; null clears the configured logo. */
  logo?:string|null;
  /** Current server-owned lifecycle state of this resource. */
  status?:'DRAFT'|'ACTIVE'}
export interface BrandEdit {
  /** Human-readable name displayed for this business resource. */
  name:string;
  /** Brand logo URL; null clears the configured logo. */
  logo?:string|null;
  /** Previously observed resource revision; stale writes are rejected. */
  expectedRevision:number}
export interface BrandImpact {
  /** Whether the server permits this operation after checking all constraints. */
  allowed:boolean;
  /** Whether the operator may approve or reject this checked change. */
  canDecide?:boolean;
  /** Server-provided reasons preventing the requested operation. */
  blockers:string[];
  /** Server-issued token binding submission to the validated change preview. */
  checkToken:string;
  /** Store revision validated by the impact check. */
  storeVersion:number;
  /** Expected revision of the existing brand/store binding. */
  bindingRevision:number;
  /** Revision of the source brand validated by the impact check. */
  fromRevision:number|null;
  /** Destination-brand revision validated by the impact check. */
  toRevision:number|null}
export interface BrandChangeSubmit {
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Destination brand identifier; null removes the store's brand binding. */
  toBrandNo:string|null;
  /** Server-issued token binding submission to the validated change preview. */
  checkToken:string;
  /** Previously observed store revision for the binding change. */
  expectedStoreRevision:number;
  /** Previously observed management-scope revision for the binding change. */
  expectedScopeRevision:number;
  /** Previously observed source-brand revision; null when there was no source brand. */
  expectedFromBrandRevision:number|null;
  /** Previously observed destination-brand revision; null when removing the binding. */
  expectedToBrandRevision:number|null;
  /** Business reason recorded for the operation, rejection or manual recovery. */
  reason:string}
export interface BrandChange {

  /** Whether this brand-binding change uses the financial stored-value migration workflow. */
  financial?:boolean;
  /** Identifier of the requested brand/store binding change. */
  changeNo:string;
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo:string;
  /** Previous brand identifier; absent when the store was unbranded. */
  fromBrandNo?:string;
  /** Destination brand identifier; null removes the store's brand binding. */
  toBrandNo?:string;
  /** Current server-owned lifecycle state of this resource. */
  status:string;
  /** Current revision used for optimistic concurrency or snapshot traceability. */
  revision:number;
  /** Whether the accepted change has been applied to the store's current binding. */
  applied:boolean;
  /** Server-provided reasons preventing the requested operation. */
  blockers?:string[];
  /** Actions currently permitted by the server for this resource. */
  actions?:string[];
  /** Business reason recorded for the operation, rejection or manual recovery. */
  reason?:string}
export interface OperatingContext {
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo:string;
  /** Operating entity identifier; authorization remains server-derived. */
  entityNo:string;
  /** Human-readable name displayed for this business resource. */
  name:string;
  /** Current server-owned lifecycle state of this resource. */
  status:string;
  /** Current revision used for optimistic concurrency or snapshot traceability. */
  revision:number;
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Current effective brand binding; null for an unbranded store. */
  brand:{
  /** Brand identifier; null when the scope has no brand binding. */
  brandNo:string;
  /** Human-readable name displayed for this business resource. */
  name:string;
  /** Current revision of the resource's management-scope bindings. */
  scopeRevision:number;storedValueStatus?:string}|null;
  /** Actions currently permitted by the server for this resource. */
  actions:string[];
  /** Unfinished store/brand binding change; null when none exists. */
  pendingChange?:{
  /** Identifier of the requested brand/store binding change. */
  changeNo:string;
  /** Current server-owned lifecycle state of this resource. */
  status:string;
  /** Current revision used for optimistic concurrency or snapshot traceability. */
  revision:number}|null}
export interface PaymentMerchant {
  /** Payment merchant identifier under the operating entity. */
  payMerchantNo:string;
  /** Operating entity identifier; authorization remains server-derived. */
  entityNo:string;
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo:string;
  /** Payment channel used by this payment-merchant capability. */
  payChannel:string;
  /** Payment merchant's onboarding application status. */
  applyStatus:string;
  /** Payment methods supported by the selected payment merchant. */
  payMethods:string[]}
export interface ManagementAudit {
  /** Operation recorded for this command or audit entry. */
  action:string;
  /** Authenticated operator identity recorded by the server. */
  actorKey:string;
  /** Time the server recorded the business operation. */
  occurredAt:string}
export interface ManagementRecipient {
  /** Identity receiving the resource grant. */
  actorNo:string;
  /** Display name of the selected customer or grant recipient. */
  displayName:string}
export interface ManagementApi {
 mManagementRecipients(m:string,cursor?:string):Promise<ManagementPage<ManagementRecipient>>;
 mManagementContext():Promise<ManagementContext>;
 mCreateManagementSpace(body:{
  /** Human-readable name displayed for this business resource. */
  name:string;
  /** Authority-source account used to bootstrap the management space. */
  sourceAccountNo:string},key:string):Promise<ManagementSpace>;
 mManagementResources(m:string,query?:{
  /** Business resource category used to resolve resourceNo and enforce authorization. */
  resourceType?:string;
  /** Whether to query effective resources or explicit capability grants. */
  view?:'EFFECTIVE'|'GRANTS';
  /** Identity receiving the resource grant. */
  actorNo?:string;
  /** Opaque continuation cursor from the preceding page; omit for the first page. */
  cursor?:string}):Promise<ManagementPage<ManagementGrant>>;
 mManagementGrant(m:string,body:GrantCommand):Promise<ManagementGrant>;
 mRevokeManagementGrant(m:string,g:string,revision:number,reason:string):Promise<ManagementGrant>;
 mManagementEntities(m:string,cursor?:string):Promise<ManagementPage<ManagementResource>>;
 mManagementStores(m:string,query?:{
  /** Brand identifier; null when the scope has no brand binding. */
  brandNo?:string;
  /** Opaque continuation cursor from the preceding page; omit for the first page. */
  cursor?:string}):Promise<ManagementPage<ManagementResource>>;
 mPaymentMerchantCandidates(e:string,m:string,query?:{
  /** Payment channel used by this payment-merchant capability. */
  payChannel?:string;
  /** Payment scene used to select a compatible payment merchant. */
  payScene?:string;
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo?:string}):Promise<PaymentMerchant[]>;
 mBrands(m:string,query?:{
  /** Current server-owned lifecycle state of this resource. */
  status?:string;
  /** Search text used to match resource names. */
  keyword?:string;
  /** Opaque continuation cursor from the preceding page; omit for the first page. */
  cursor?:string}):Promise<ManagementPage<Brand>>;
 mCreateBrand(body:BrandDraft,key:string):Promise<Brand>;
 mBrand(b:string):Promise<Brand>;
 mEditBrand(b:string,body:BrandEdit,key:string):Promise<Brand>;
 mBrandStatus(b:string,body:{
  /** Requested lifecycle state; server checks permitted transitions. */
  targetStatus:'ACTIVE'|'INACTIVE';
  /** Previously observed resource revision; stale writes are rejected. */
  expectedRevision:number;
  /** Business reason recorded for the operation, rejection or manual recovery. */
  reason:string},key:string):Promise<Brand>;
 mBrandStores(b:string,cursor?:string):Promise<ManagementPage<ManagementResource>>;
 mBrandAudit(b:string,cursor?:string):Promise<ManagementPage<ManagementAudit>>;
 mStoreOperatingContext(s:string,m:string):Promise<OperatingContext>;
 mCheckBrandChange(s:string,body:{
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Destination brand identifier; null removes the store's brand binding. */
  toBrandNo:string|null}):Promise<BrandImpact>;
 mSubmitBrandChange(s:string,body:BrandChangeSubmit,key:string):Promise<BrandChange>;
 mBrandChange(c:string):Promise<BrandChange>;
 mDecideBrandChange(c:string,body:{
  /** Approval or return decision submitted for the current revision. */
  decision:'APPROVE'|'REJECT';
  /** Previously observed resource revision; stale writes are rejected. */
  expectedRevision:number;
  /** Business reason recorded for the operation, rejection or manual recovery. */
  reason:string},key:string):Promise<BrandChange>;
}
