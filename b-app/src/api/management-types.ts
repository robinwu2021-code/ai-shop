export interface ManagementPage<T> {
  /** 当前页业务记录 */
  items:T[];
  /** 下一页游标，无后续记录时为空 */
  nextCursor:string|null}
export interface ManagementResource {
  /** 是否允许使用原经营主体管理入口 */
  canManageLegacy?:boolean;
  /** 当前账号是否允许配置品牌 */
  canConfigureBrand?:boolean;
  /** 被授权资源类型编码 */
  resourceType?:string;
  /** 被授权资源编号 */
  resourceNo?:string;
  /** 业务对象显示名称 */
  name:string;
  /** 服务端业务状态编码 */
  status:string;
  /** 当前记录版本号，用于并发控制 */
  revision:number;
  /** 品牌编号 */
  brandNo?:string;
  /** 经营主体编号 */
  entityNo?:string;
  /** 门店编号 */
  storeNo?:string;
  /** 资源可见范围版本号 */
  scopeRevision?:number}
export interface ManagementSpace extends ManagementResource {
  /** 经营管理空间编号 */
  managementNo:string;
  /** 当前账号可执行的动作集合 */
  actions:string[]}
export interface ManagementContext {
  /** 认证管理账号标识 */
  actorKey:string;
  /** 当前账号可见的经营管理空间列表 */
  spaces:ManagementSpace[];
  /** 是否允许创建初始管理空间 */
  canBootstrap:boolean;
  /** 当前账号可用于创建管理空间的授权来源 */
  bootstrapSources:Array<{accountNo:string;
  /** 经营主体编号 */
  entityNo:string;entityName?:string;stores:Array<{
  /** 门店编号 */
  storeNo:string;
  /** 业务对象显示名称 */
  name:string}>;paymentMerchants:Array<{
  /** 支付商户编号 */
  payMerchantNo:string}>}>}
export interface Brand extends ManagementResource {
  /** 品牌编号 */
  brandNo:string;
  /** 经营管理空间编号 */
  managementNo:string;
  /** 资源可见范围版本号 */
  scopeRevision:number;
  /** 品牌标志资源地址 */
  logo?:string;
  /** 品牌下可见门店数量 */
  storeCount?:number;
  /** 创建时间 */
  createdAt?:string;
  /** 最近更新时间 */
  updatedAt?:string;
  /** 创建人显示名称 */
  createdByLabel?:string;
  /** 最近更新人显示名称 */
  updatedByLabel?:string;
  /** 当前账号可执行的动作集合 */
  actions?:string[]}
export interface ManagementGrant {
  /** 操作人显示名称 */
  actorLabel?:string;
  /** 被授权资源显示名称 */
  resourceName?:string;
  /** 授权记录编号 */
  grantNo:string;
  /** 经营管理空间编号 */
  managementNo:string;
  /** 被授权管理账号编号 */
  actorNo:string;
  /** 被授权资源类型编码 */
  resourceType:string;
  /** 被授权资源编号 */
  resourceNo:string;
  /** 经营管理授权能力码 */
  permissionCode:string;
  /** 服务端业务状态编码 */
  status:string;
  /** 是否允许被授权人继续转授 */
  delegatable:boolean|number;
  /** 当前记录版本号，用于并发控制 */
  revision:number}
export interface GrantCommand {
  /** 被授权管理账号编号 */
  actorNo:string;
  /** 被授权资源类型编码 */
  resourceType:string;
  /** 被授权资源编号 */
  resourceNo:string;
  /** 经营管理授权能力码 */
  permissionCode:string;
  /** 是否允许被授权人继续转授 */
  delegatable?:boolean;
  /** 授权有效期开始时间 */
  validFrom?:string;
  /** 授权有效期截止时间 */
  validTo?:string;
  /** 调用方读取到的版本号，用于乐观锁校验 */
  expectedRevision?:number;
  /** 是否重新激活已存在的授权 */
  reactivate?:boolean}
export interface BrandDraft {
  /** 经营管理空间编号 */
  managementNo:string;
  /** 业务对象显示名称 */
  name:string;
  /** 品牌标志资源地址 */
  logo?:string|null;
  /** 服务端业务状态编码 */
  status?:'DRAFT'|'ACTIVE'}
export interface BrandEdit {
  /** 业务对象显示名称 */
  name:string;
  /** 品牌标志资源地址 */
  logo?:string|null;
  /** 调用方读取到的版本号，用于乐观锁校验 */
  expectedRevision:number}
export interface BrandImpact {
  /** 当前业务校验是否允许继续 */
  allowed:boolean;
  /** 当前账号是否允许审批此变更 */
  canDecide?:boolean;
  /** 阻断原因编码列表 */
  blockers:string[];
  /** 服务端签发的短时检查凭据，提交时原样传回 */
  checkToken:string;
  /** 检查时门店版本号 */
  storeVersion:number;
  /** 门店品牌绑定关系版本号 */
  bindingRevision:number;
  /** 检查时原品牌版本号 */
  fromRevision:number|null;
  /** 检查时目标品牌版本号 */
  toRevision:number|null}
export interface BrandChangeSubmit {
  /** 经营管理空间编号 */
  managementNo:string;
  /** 目标品牌编号；解除绑定时为空 */
  toBrandNo:string|null;
  /** 服务端签发的短时检查凭据，提交时原样传回 */
  checkToken:string;
  /** 检查时门店版本，用于并发校验 */
  expectedStoreRevision:number;
  /** 检查时资源范围版本，用于并发校验 */
  expectedScopeRevision:number;
  /** 检查时原品牌版本，提交时进行乐观锁校验 */
  expectedFromBrandRevision:number|null;
  /** 检查时目标品牌版本，用于并发校验 */
  expectedToBrandRevision:number|null;
  /** 业务原因或阻断说明 */
  reason:string}
export interface BrandChange {
  /** 品牌变更业务编号 */
  changeNo:string;
  /** 经营管理空间编号 */
  managementNo:string;
  /** 门店编号 */
  storeNo:string;
  /** 变更前品牌编号 */
  fromBrandNo?:string;
  /** 目标品牌编号；解除绑定时为空 */
  toBrandNo?:string;
  /** 服务端业务状态编码 */
  status:string;
  /** 当前记录版本号，用于并发控制 */
  revision:number;
  /** 变更是否已经应用到有效关系 */
  applied:boolean;
  /** 阻断原因编码列表 */
  blockers?:string[];
  /** 当前账号可执行的动作集合 */
  actions?:string[];
  /** 业务原因或阻断说明 */
  reason?:string}
export interface OperatingContext {
  /** 门店编号 */
  storeNo:string;
  /** 经营主体编号 */
  entityNo:string;
  /** 业务对象显示名称 */
  name:string;
  /** 服务端业务状态编码 */
  status:string;
  /** 当前记录版本号，用于并发控制 */
  revision:number;
  /** 经营管理空间编号 */
  managementNo:string;
  /** 当前有效品牌摘要 */
  brand:{
  /** 品牌编号 */
  brandNo:string;
  /** 业务对象显示名称 */
  name:string;
  /** 资源可见范围版本号 */
  scopeRevision:number}|null;
  /** 当前账号可执行的动作集合 */
  actions:string[];
  /** 尚未完成的品牌变更摘要 */
  pendingChange?:{
  /** 品牌变更业务编号 */
  changeNo:string;
  /** 服务端业务状态编码 */
  status:string;
  /** 当前记录版本号，用于并发控制 */
  revision:number}|null}
export interface PaymentMerchant {
  /** 支付商户编号 */
  payMerchantNo:string;
  /** 经营主体编号 */
  entityNo:string;
  /** 门店编号 */
  storeNo:string;
  /** 支付渠道编码 */
  payChannel:string;
  /** 支付商户申请状态 */
  applyStatus:string;
  /** 已开通支付方式列表 */
  payMethods:string[]}
export interface ManagementAudit {
  /** 本次执行的业务动作 */
  action:string;
  /** 认证管理账号标识 */
  actorKey:string;
  /** 业务实际发生时间 */
  occurredAt:string}
export interface ManagementRecipient {
  /** 被授权管理账号编号 */
  actorNo:string;
  /** 脱敏显示名称 */
  displayName:string}
export interface ManagementApi {
 mManagementRecipients(m:string,cursor?:string):Promise<ManagementPage<ManagementRecipient>>;
 mManagementContext():Promise<ManagementContext>;
 mCreateManagementSpace(body:{
  /** 业务对象显示名称 */
  name:string;
  /** 创建管理空间所使用的来源账号编号 */
  sourceAccountNo:string},key:string):Promise<ManagementSpace>;
 mManagementResources(m:string,query?:{
  /** 被授权资源类型编码 */
  resourceType?:string;
  /** 资源查询视图编码 */
  view?:'EFFECTIVE'|'GRANTS';
  /** 被授权管理账号编号 */
  actorNo?:string;
  /** 上一页返回的分页游标，首屏可省略 */
  cursor?:string}):Promise<ManagementPage<ManagementGrant>>;
 mManagementGrant(m:string,body:GrantCommand):Promise<ManagementGrant>;
 mRevokeManagementGrant(m:string,g:string,revision:number,reason:string):Promise<ManagementGrant>;
 mManagementEntities(m:string,cursor?:string):Promise<ManagementPage<ManagementResource>>;
 mManagementStores(m:string,query?:{
  /** 品牌编号 */
  brandNo?:string;
  /** 上一页返回的分页游标，首屏可省略 */
  cursor?:string}):Promise<ManagementPage<ManagementResource>>;
 mPaymentMerchantCandidates(e:string,m:string,query?:{
  /** 支付渠道编码 */
  payChannel?:string;
  /** 支付场景编码 */
  payScene?:string;
  /** 门店编号 */
  storeNo?:string}):Promise<PaymentMerchant[]>;
 mBrands(m:string,query?:{
  /** 服务端业务状态编码 */
  status?:string;
  /** 关键词筛选条件 */
  keyword?:string;
  /** 上一页返回的分页游标，首屏可省略 */
  cursor?:string}):Promise<ManagementPage<Brand>>;
 mCreateBrand(body:BrandDraft,key:string):Promise<Brand>;
 mBrand(b:string):Promise<Brand>;
 mEditBrand(b:string,body:BrandEdit,key:string):Promise<Brand>;
 mBrandStatus(b:string,body:{
  /** 待切换的业务状态 */
  targetStatus:'ACTIVE'|'INACTIVE';
  /** 调用方读取到的版本号，用于乐观锁校验 */
  expectedRevision:number;
  /** 业务原因或阻断说明 */
  reason:string},key:string):Promise<Brand>;
 mBrandStores(b:string,cursor?:string):Promise<ManagementPage<ManagementResource>>;
 mBrandAudit(b:string,cursor?:string):Promise<ManagementPage<ManagementAudit>>;
 mStoreOperatingContext(s:string,m:string):Promise<OperatingContext>;
 mCheckBrandChange(s:string,body:{
  /** 经营管理空间编号 */
  managementNo:string;
  /** 目标品牌编号；解除绑定时为空 */
  toBrandNo:string|null}):Promise<BrandImpact>;
 mSubmitBrandChange(s:string,body:BrandChangeSubmit,key:string):Promise<BrandChange>;
 mBrandChange(c:string):Promise<BrandChange>;
 mDecideBrandChange(c:string,body:{
  /** 审批决定编码 */
  decision:'APPROVE'|'REJECT';
  /** 调用方读取到的版本号，用于乐观锁校验 */
  expectedRevision:number;
  /** 业务原因或阻断说明 */
  reason:string},key:string):Promise<BrandChange>;
}
