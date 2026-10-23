import type {SvPage,SvPlan,SvProgress} from './stored-value-types';
export interface IssuerRoute {
  /** 收款配置引用的支付商户编号 */
  paymentMerchantNo:string|null;
  /** 经营主体编号 */
  entityNo:string;
  /** 门店编号 */
  storeNo:string|null;
  /** 消息或支付通道编码 */
  channel:string;
  /** 支付或业务场景编码 */
  scene:string}
export interface IssuerConfiguration {
  /** 业务对象显示名称 */
  name:string;
  /** 承担发行结算责任的经营主体编号 */
  settlementEntityNo:string;
  /** 发行方收款模式编码 */
  collectionMode:string;
  /** 发行方清分模式编码 */
  clearingMode:string;
  /** 消费扣减的资金分配模式编码 */
  debitAllocationMode:string;
  /** ISO货币代码，金额必须结合此字段解释 */
  currency:string;
  /** 市场及时区配置编码 */
  marketZone:string;
  /** 生效支付收款路由列表 */
  routes:IssuerRoute[];
  /** 调用方读取到的版本号，用于乐观锁校验 */
  expectedRevision:number}
export interface IssuerView extends Omit<IssuerConfiguration,'expectedRevision'> {
  /** 储值发行方编号 */
  issuerNo:string;
  /** 不可变发行配置版本编号 */
  issuerVersionNo:string;
  /** 储值发行范围类型：STORE或BRAND */
  scopeType:'STORE'|'BRAND';
  /** 储值发行范围编号 */
  scopeNo:string;
  /** 服务端业务状态编码 */
  status:string;
  /** 当前记录版本号，用于并发控制 */
  revision:number}
export interface IssuerImpact {
  /** 当前业务校验是否允许继续 */
  allowed:boolean;
  /** 受配置变更影响的账户数量 */
  affectedAccountCount:number;
  /** 是否存在尚未结清的相关业务 */
  hasPending:boolean;
  /** 阻断原因编码列表 */
  blockers:string[]}
export interface StoreIssuerOption {
  /** 储值发行方编号 */
  issuerNo:string;
  /** 储值发行范围类型：STORE或BRAND */
  scopeType:'STORE';
  /** 储值发行范围编号 */
  scopeNo:string;
  /** 业务对象显示名称 */
  name:string;
  /** ISO货币代码，金额必须结合此字段解释 */
  currency:string;
  /** 服务端业务状态编码 */
  status:string;
  /** 发行方允许的写入模式编码 */
  writeMode:string;
  /** 当前条件是否允许充值 */
  canRecharge:boolean;
  /** 当前发行配置是否允许消费核销 */
  canRedeem:boolean;
  /** 当前业务不可用的原因编码 */
  unavailableReason:string|null}
export interface StoreIssuerOptions {
  /** 门店编号 */
  storeNo:string;
  /** 当前是否使用兼容储值模式 */
  legacyMode:boolean;
  /** 可用储值发行方列表 */
  issuers:StoreIssuerOption[]}
export interface IssuerConfigureRequest {
  /** 经营管理空间编号 */
  managementNo:string;
  /** 待校验或保存的发行配置 */
  configuration:IssuerConfiguration}
export interface IssuerAccessAck {
  /** 储值发行方编号 */
  issuerNo:string;
  /** 门店编号 */
  storeNo:string;
  /** 来源配置版本号 */
  sourceRevision:number;
  /** 服务端业务状态编码 */
  status:string}
export interface IssuerApi {
 mSvStoreIssuerConfiguration(no:string,managementNo:string):Promise<{
  /** 当前生效发行方摘要；未配置时为空 */
  issuer:IssuerView|null}>;
 mSvConfigureStoreIssuer(no:string,body:IssuerConfigureRequest,key:string):Promise<IssuerView>;
 mSvIssuer(type:'STORE'|'BRAND',no:string,managementNo:string):Promise<{
  /** 当前生效发行方摘要；未配置时为空 */
  issuer:IssuerView|null}>;
 mSvConfigureIssuer(type:'STORE'|'BRAND',no:string,body:IssuerConfigureRequest,key:string):Promise<IssuerView>;
 mSvIssuerImpact(no:string,body:IssuerConfigureRequest):Promise<IssuerImpact>;
 mSvIssuerVersion(no:string,body:IssuerConfigureRequest,key:string):Promise<IssuerView>;
 mSvIssuerEntities(managementNo:string,scopeType:'STORE'|'BRAND',scopeNo:string):Promise<Array<{
  /** 经营主体编号 */
  entityNo:string;
  /** 业务对象显示名称 */
  name:string;
  /** 是否已接受该经营主体 */
  accepted:boolean;
  /** 是否允许接受该发行配置 */
  canAccept:boolean}>>;
 mSvStoreIssuers(storeNo:string):Promise<StoreIssuerOptions>;
 mSvRechargeOptions(query:{
  /** 门店编号 */
  storeNo:string;
  /** 当前数据域签发的顾客选择引用 */
  customerRef:string;
  /** 上一页返回的分页游标，首屏可省略 */
  cursor?:string;
  /** 请求的每页记录数量 */
  size?:number}):Promise<{
  /** 门店可用发行方选项 */
  issuerOptions:StoreIssuerOptions;
  /** 可用充值方案分页 */
  plans:SvPage<SvPlan>}>;
 mSvIssuerRoutes(no:string,managementNo:string):Promise<IssuerRoute[]>;
 mSvPrepareIssuer(storeNo:string,managementNo:string):Promise<IssuerView>;
 mSvRefreshIssuer(storeNo:string,managementNo:string):Promise<IssuerAccessAck>;
 mSvIssuerRecharge(rechargeNo:string,storeNo:string):Promise<SvProgress>;
 mSvIssuerCancel(rechargeNo:string,storeNo:string,body:{
  /** 调用方读取到的版本号，用于乐观锁校验 */
  expectedRevision:number;
  /** 业务原因或阻断说明 */
  reason:string},key:string):Promise<SvProgress>;
}
