import type {CurrencyCode} from '@shared/types';
import type {SvPage} from './stored-value-types';
export interface SvPolicyRules {
  /** Whether the policy permits new stored-value issuance. */
  allowIssue:boolean;
  /** Whether new activities under this policy require approval before publication. */
  requireApproval:boolean;
  /** Maximum bonus-to-paid ratio in basis points; 10000 means 100 percent. */
  maxBonusBps:number;
  /** Maximum permitted recharge principal, in minor currency units. */
  maxPaidMinor:number;
  /** Maximum gifted amount per activity tier, in minor currency units. */
  maxBonusMinor:number;
  /** Maximum permitted activity duration in days. */
  maxActiveDays:number;
  /** Maximum permitted outstanding stored-value liability, in minor currency units. */
  maxOutstandingMinor:number}
export type SvPolicyTarget='SPACE'|'BRAND_STORES'|'STORE';
export interface SvPolicyTargetOption {
/** Record, target or calculation classification used to interpret this object. */
type:SvPolicyTarget;
/** Business identifier of the selected policy target. */
no:string;
/** Human-readable name displayed for this business resource. */
name:string;
/** Whether the current operator may change this governance resource. */
canManage:boolean}
export interface SvGovernanceContext {
/** ISO currency code determining the interpretation of monetary amounts. */
currency:CurrencyCode;
/** Number of decimal places in the currency's major unit. */
currencyScale:number;
/** IANA time zone used for business-date filtering and activity scheduling. */
timeZone:string;
/** Whether the current operator may change this governance resource. */
canManage:boolean;
/** Whether the operator may publish activities under this effective policy. */
canPublish:boolean}
export interface SvPolicy {
/** Stored-value governance policy identifier. */
policyNo:string;
/** Immutable version identifier frozen for the referenced plan or policy. */
versionNo:string;
/** Current revision used for optimistic concurrency or snapshot traceability. */
revision:number;
/** Whether the governance policy applies to a space, brand stores or a single store. */
targetType:SvPolicyTarget;
/** Business identifier selected by the policy's targetType. */
targetNo:string;
/** ISO currency code determining the interpretation of monetary amounts. */
currency:CurrencyCode;
/** Effective stored-value issuance limits and approval requirements. */
rules:SvPolicyRules;
/** Immutable parent-policy versions inherited by this policy revision. */
parentVersionRefs:string[];
/** Hash of the immutable policy contents used to detect stale submissions. */
contentHash:string}
export interface SvPolicyVersion {
/** Tenant namespace attached by the server to this policy snapshot. */
tenantNo:string;
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Stored-value governance policy identifier. */
policyNo:string;
/** Immutable version identifier frozen for the referenced plan or policy. */
versionNo:string;
/** Whether the governance policy applies to a space, brand stores or a single store. */
targetType:SvPolicyTarget;
/** Business identifier selected by the policy's targetType. */
targetNo:string;
/** ISO currency code determining the interpretation of monetary amounts. */
currency:CurrencyCode;
/** Effective stored-value issuance limits and approval requirements. */
rules:SvPolicyRules}
export interface SvEffectivePolicy {
/** Tenant, management space, store, brand and currency used to resolve this effective policy. */
scope:{
/** Tenant namespace attached by the server to this policy snapshot. */
tenantNo:string;
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Store identifier scoping the requested operation or returned business record. */
storeNo:string;
/** Brand identifier; null when the scope has no brand binding. */
brandNo:string|null;
/** ISO currency code determining the interpretation of monetary amounts. */
currency:CurrencyCode;
/** Store revision included in the effective-policy snapshot. */
storeRevision:number};
/** Effective stored-value issuance limits and approval requirements. */
rules:SvPolicyRules;
/** Policy versions contributing to the resolved effective policy. */
sources:SvPolicyVersion[];
/** Hash of the resolved effective policy and its authority sources. */
hash:string;
/** Whether an explicit effective policy has been configured. */
configured:boolean}
export interface SvActivityDefinition {
/** Human-readable name displayed for this business resource. */
name:string;
/** Business scene in which the route or activity is applicable. */
scene:'RECHARGE';
/** Activity preset code identifying the supported definition template. */
templateCode:string;
/** Version of the selected activity preset. */
templateVersion:number;
/** Stores allowed to offer this recharge activity. */
storeParticipationScope:{
/** Calculation or scope mode determining how the associated value and references are interpreted. */
mode:'ALL'|'STORES';
/** Business identifiers selected by the associated scope mode. */
refs:string[]};
/** Audience selection reference; null when no restricted audience is configured. */
audienceRef:string|null;
/** Activity calendar including zone, date window and recurring local-time intervals. */
schedule:{
/** Record, target or calculation classification used to interpret this object. */
type:string;
/** Start of the configured activity window as a server timestamp. */
startAt:string;
/** End of the configured activity window as a server timestamp. */
endAt:string;
/** IANA time zone used to interpret the activity schedule. */
zone:string;
/** Allowed weekdays for recurring participation in the activity's business zone. */
weekdays:number[];
/** Start of the recurring local-time interval; null when the interval is not restricted. */
from:string|null;
/** End of the recurring local-time interval; null when the interval is not restricted. */
to:string|null};
/** Immutable calculation rule or versioned activity rule definition. */
rule:{
/** Version of the immutable rule or order snapshot format. */
schemaVersion:number;
/** Eligibility condition applied before the activity's recharge benefit tiers. */
trigger:{
/** Record, target or calculation classification used to interpret this object. */
type:string;
/** Trigger amount in the currency's smallest unit; null when not applicable. */
amountMinor:null;
/** Product quantity used by the snapshot or trigger; null when not applicable. */
quantity:null};
/** Additional benefit definitions; currently empty for tiered recharge activities. */
benefits:never[];
/** Recharge principal/bonus tiers offered by this activity. */
tiers:Array<{
/** Stable identifier of this recharge tier within the activity definition. */
tierNo:string;
/** Required paid principal for this recharge tier, in minor currency units. */
payAmountMinor:number;
/** Gifted stored-value amount, in minor currency units. */
bonusAmountMinor:number;
/** Stores in which the gifted recharge component may later be redeemed. */
redemptionScope:{
/** Calculation or scope mode determining how the associated value and references are interpreted. */
mode:'ALL'|'STORES';
/** Business identifiers selected by the associated scope mode. */
refs:string[]}}>};
/** Maximum successful activity participations; null for no configured cap. */
quotaLimit:number|null;
/** Total activity budget limit in minor currency units; null for no configured cap. */
budgetLimitMinor:number|null;
/** Maximum number of successful participations per customer. */
perUserLimit:number;
/** ISO currency code determining the interpretation of monetary amounts. */
currency:CurrencyCode}
export interface SvActivity {
/** Stored-value activity identifier. */
activityNo:string;
/** Current revision used for optimistic concurrency or snapshot traceability. */
revision:number;
/** Current server-owned lifecycle state of this resource. */
status:string;
/** Current published activity version; null before first publication. */
currentVersionNo:string|null;
/** Versioned activity definition, including schedule, scope and recharge tiers. */
definition:SvActivityDefinition;
/** Human-facing document number; null or absent for historical unmapped records. */
documentNo?:string|null}
export interface SvSubmission {
/** Durable link between a draft, its approval and eventual publication. */
linkNo:string;
/** Stored-value activity identifier. */
activityNo:string;
/** Immutable draft revision identifier linked to the submission. */
draftRevisionNo:string;
/** Hash of the exact activity draft submitted for validation or approval. */
draftHash:string;
/** Current processing projection for the submitted business operation. */
state:string;
/** Durable publication-release identifier; null before preparation. */
releaseNo:string|null;
/** Hash of the published release; null before release preparation completes. */
releaseHash:string|null;
/** Hash of the effective policy frozen when the draft was submitted. */
policyHash:string;
/** Whether publication must obtain a separate approval first. */
approvalRequired:boolean;
/** Approval request identifier; null when approval is not required or not yet created. */
approvalNo:string|null;
/** Published activity-version identifier; null until publication completes. */
activityVersionNo:string|null}
export interface SvApproval {
/** Approval request identifier; null when approval is not required or not yet created. */
approvalNo:string;
/** Durable link between a draft, its approval and eventual publication. */
linkNo:string;
/** Stored-value activity identifier. */
activityNo:string;
/** Immutable draft revision identifier linked to the submission. */
draftRevisionNo:string;
/** Hash of the exact activity draft submitted for validation or approval. */
draftHash:string;
/** Store identifier scoping the requested operation or returned business record. */
storeNo:string|null;
/** Whether the selected stored-value resource belongs to one store or a brand. */
scopeType?:'STORE'|'BRAND';
/** Business identifier of the store or brand selected by scopeType. */
scopeNo?:string;
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Current server-owned lifecycle state of this resource. */
status:string;
/** Hash of the effective policy frozen when the draft was submitted. */
policyHash:string;
/** Authenticated operator who submitted the approval request. */
submittedBy:string;
/** Server-recorded decision operator; null while awaiting a decision. */
decidedBy:string|null;
/** Current revision used for optimistic concurrency or snapshot traceability. */
revision:number;
/** Business reason recorded for the operation, rejection or manual recovery. */
reason:string|null;
/** Versioned activity definition, including schedule, scope and recharge tiers. */
definition:SvActivityDefinition}
export interface SvActivityCheck {
/** Resolved effective governance policy used by the activity validation. */
policy:SvEffectivePolicy;
/** Whether the server permits this operation after checking all constraints. */
allowed:boolean;
/** Whether publication must obtain a separate approval first. */
approvalRequired:boolean;
/** Server-provided reasons preventing the requested operation. */
blockers:string[]}
export interface SvGovCommand {
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** ISO currency code determining the interpretation of monetary amounts. */
currency:CurrencyCode;
/** Previously observed resource revision; stale writes are rejected. */
expectedRevision:number}
export interface StoredValueGovernanceApi {

/** Brand-scoped counterpart of mSvActivityContext with the same operation contract. */
mSvBrandActivityContext:StoredValueGovernanceApi["mSvActivityContext"];

/** Brand-scoped counterpart of mSvActivityPresets with the same operation contract. */
mSvBrandActivityPresets:StoredValueGovernanceApi["mSvActivityPresets"];

/** Brand-scoped counterpart of mSvActivitySchedule with the same operation contract. */
mSvBrandActivitySchedule:StoredValueGovernanceApi["mSvActivitySchedule"];

/** Brand-scoped counterpart of mSvActivities with the same operation contract. */
mSvBrandActivities:StoredValueGovernanceApi["mSvActivities"];

/** Brand-scoped counterpart of mSvActivity with the same operation contract. */
mSvBrandActivity:StoredValueGovernanceApi["mSvActivity"];

/** Brand-scoped counterpart of mSvActivitySave with the same operation contract. */
mSvBrandActivitySave:StoredValueGovernanceApi["mSvActivitySave"];

/** Brand-scoped counterpart of mSvActivityDraftEdit with the same operation contract. */
mSvBrandActivityDraftEdit:StoredValueGovernanceApi["mSvActivityDraftEdit"];

/** Brand-scoped counterpart of mSvActivityCheck with the same operation contract. */
mSvBrandActivityCheck:StoredValueGovernanceApi["mSvActivityCheck"];

/** Brand-scoped counterpart of mSvActivitySubmit with the same operation contract. */
mSvBrandActivitySubmit:StoredValueGovernanceApi["mSvActivitySubmit"];

/** Brand-scoped counterpart of mSvActivityStop with the same operation contract. */
mSvBrandActivityStop:StoredValueGovernanceApi["mSvActivityStop"];

/** Brand-scoped counterpart of mSvActivityCopy with the same operation contract. */
mSvBrandActivityCopy:StoredValueGovernanceApi["mSvActivityCopy"];

 mSvActivityDraftEdit(store:string,no:string,body:{
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** Versioned activity definition, including schedule, scope and recharge tiers. */
definition:SvActivityDefinition;
/** Previously observed resource revision; stale writes are rejected. */
expectedRevision?:number},key:string):Promise<SvActivity>;
 mSvPreparePolicyPermissions(managementNo:string):Promise<{
/** Whether the requested permission preparation has completed. */
prepared:boolean}>;
 mSvActivityContext(store:string,q:{
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** ISO currency code determining the interpretation of monetary amounts. */
currency:CurrencyCode;
/** Requested governance capability: read-only inspection or management. */
intent:'READ'|'MANAGE'}):Promise<SvGovernanceContext>;
 mSvActivityPresets(store:string,q:{
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** ISO currency code determining the interpretation of monetary amounts. */
currency:CurrencyCode;
/** One-based page index for the returned or requested result. */
page?:number;
/** Maximum records per page requested or returned by the server. */
size?:number}):Promise<{
/** Business records returned by the current query. */
records:Array<{
/** Activity preset code identifying the supported definition template. */
templateCode:string;
/** Version of the selected activity preset. */
templateVersion:number;
/** Human-readable name displayed for this business resource. */
name:string;
/** Whether this feature or preset is enabled in the current deployment. */
enabled:boolean;
/** Whether this capability or preset is available in the current deployment. */
available:boolean;
/** Deployment capabilities missing for this preset to be usable. */
missingCapabilities:string[]}>;
/** Total number of records matching the current query. */
total:number}>;
 mSvActivitySchedule(store:string,q:{
/** Management-space identifier under which the operation is authorized. */
managementNo:string;
/** ISO currency code determining the interpretation of monetary amounts. */
currency:CurrencyCode;
/** Inclusive first business date in YYYY-MM-DD format. */
startDate:string;
/** Inclusive final business date in YYYY-MM-DD format. */
endDate:string}):Promise<{
/** Start of the configured activity window as a server timestamp. */
startAt:string;
/** End of the configured activity window as a server timestamp. */
endAt:string;
/** IANA time zone used to interpret the activity schedule. */
zone:string;
/** ISO currency code determining the interpretation of monetary amounts. */
currency:CurrencyCode;
/** Number of decimal places in the currency's major unit. */
currencyScale:number}>;
 mSvGovernanceCapability():Promise<{
/** Capability protocol version supported by the current server. */
protocol:number;
/** Whether this feature or preset is enabled in the current deployment. */
enabled:boolean}>;
 mSvPolicyTargets(managementNo:string):Promise<SvPolicyTargetOption[]>;
 mSvPolicies(q:{
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:CurrencyCode;
  /** Whether the governance policy applies to a space, brand stores or a single store. */
  targetType?:SvPolicyTarget;
  /** Business identifier selected by the policy's targetType. */
  targetNo?:string}):Promise<SvPolicy[]>;
 mSvPolicyCreate(body:{
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Whether the governance policy applies to a space, brand stores or a single store. */
  targetType:SvPolicyTarget;
  /** Business identifier selected by the policy's targetType. */
  targetNo:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:CurrencyCode;
  /** Effective stored-value issuance limits and approval requirements. */
  rules:SvPolicyRules},key:string):Promise<SvPolicy>;
 mSvPolicyEdit(no:string,body:{
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Proposed policy revision and its optimistic concurrency check. */
  edit:{
  /** Effective stored-value issuance limits and approval requirements. */
  rules:SvPolicyRules;
  /** Previously observed resource revision; stale writes are rejected. */
  expectedRevision:number}},key:string):Promise<SvPolicy>;
 mSvEffectivePolicy(store:string,q:{
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:CurrencyCode}):Promise<SvEffectivePolicy>;
 mSvActivities(store:string,q:{
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:CurrencyCode;
  /** Current server-owned lifecycle state of this resource. */
  status?:string;
  /** One-based page index for the returned or requested result. */
  page?:number;
  /** Maximum records per page requested or returned by the server. */
  size?:number}):Promise<{
  /** Business records returned by the current query. */
  records:SvActivity[];
  /** Total number of records matching the current query. */
  total:number;
  /** One-based page index for the returned or requested result. */
  page:number;
  /** Maximum records per page requested or returned by the server. */
  size:number}>;
 mSvActivity(store:string,no:string,q:{
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency:CurrencyCode}):Promise<{
  /** Stored-value activity and its current versioned definition. */
  activity:SvActivity;
  /** Durable activity submission state; null before any submission. */
  submission:SvSubmission|null}>;
 mSvActivitySave(store:string,no:string|null,body:{
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Versioned activity definition, including schedule, scope and recharge tiers. */
  definition:SvActivityDefinition;
  /** Previously observed resource revision; stale writes are rejected. */
  expectedRevision?:number},key:string):Promise<SvActivity>;
 mSvActivityCheck(store:string,no:string,body:SvGovCommand):Promise<SvActivityCheck>;
 mSvActivitySubmit(store:string,no:string,body:SvGovCommand,key:string):Promise<SvSubmission>;
 mSvActivityStop(store:string,no:string,body:SvGovCommand,key:string):Promise<SvSubmission>;
 mSvActivityCopy(store:string,no:string,body:SvGovCommand,key:string):Promise<SvActivity>;
 mSvApprovals(q:{
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Current server-owned lifecycle state of this resource. */
  status?:string;
  /** Opaque continuation cursor from the preceding page; omit for the first page. */
  cursor?:string;
  /** Maximum records per page requested or returned by the server. */
  size?:number}):Promise<SvPage<SvApproval>>;
 mSvApproval(no:string,managementNo:string):Promise<SvApproval>;
 mSvApprovalDecide(no:string,body:{
  /** Management-space identifier under which the operation is authorized. */
  managementNo:string;
  /** Approval or return decision submitted for the current revision. */
  decision:{
  /** Approval or return decision submitted for the current revision. */
  decision:'APPROVE'|'RETURN';
  /** Business reason recorded for the operation, rejection or manual recovery. */
  reason:string;
  /** Previously observed resource revision; stale writes are rejected. */
  expectedRevision:number}},key:string):Promise<SvApproval>;
}
