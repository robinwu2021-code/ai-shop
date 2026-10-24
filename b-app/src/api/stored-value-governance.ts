import type {CurrencyCode} from '@shared/types';
import type {SvPage} from './stored-value-types';
export interface SvPolicyRules {allowIssue:boolean;requireApproval:boolean;maxBonusBps:number;maxPaidMinor:number;maxBonusMinor:number;maxActiveDays:number;maxOutstandingMinor:number}
export type SvPolicyTarget='SPACE'|'BRAND_STORES'|'STORE';
export interface SvPolicyTargetOption {type:SvPolicyTarget;no:string;name:string;canManage:boolean}
export interface SvGovernanceContext {currency:CurrencyCode;currencyScale:number;timeZone:string;canManage:boolean;canPublish:boolean}
export interface SvPolicy {policyNo:string;versionNo:string;revision:number;targetType:SvPolicyTarget;targetNo:string;currency:CurrencyCode;rules:SvPolicyRules;parentVersionRefs:string[];contentHash:string}
export interface SvPolicyVersion {tenantNo:string;managementNo:string;policyNo:string;versionNo:string;targetType:SvPolicyTarget;targetNo:string;currency:CurrencyCode;rules:SvPolicyRules}
export interface SvEffectivePolicy {scope:{tenantNo:string;managementNo:string;storeNo:string;brandNo:string|null;currency:CurrencyCode;storeRevision:number};rules:SvPolicyRules;sources:SvPolicyVersion[];hash:string;configured:boolean}
export interface SvActivityDefinition {name:string;scene:'RECHARGE';templateCode:string;templateVersion:number;storeParticipationScope:{mode:'ALL'|'STORES';refs:string[]};audienceRef:string|null;schedule:{type:string;startAt:string;endAt:string;zone:string;weekdays:number[];from:string|null;to:string|null};rule:{schemaVersion:number;trigger:{type:string;amountMinor:null;quantity:null};benefits:never[];tiers:Array<{tierNo:string;payAmountMinor:number;bonusAmountMinor:number;redemptionScope:{mode:'ALL'|'STORES';refs:string[]}}>};quotaLimit:number|null;budgetLimitMinor:number|null;perUserLimit:number;currency:CurrencyCode}
export interface SvActivity {activityNo:string;revision:number;status:string;currentVersionNo:string|null;definition:SvActivityDefinition;documentNo?:string|null}
export interface SvSubmission {linkNo:string;activityNo:string;draftRevisionNo:string;draftHash:string;state:string;releaseNo:string|null;releaseHash:string|null;policyHash:string;approvalRequired:boolean;approvalNo:string|null;activityVersionNo:string|null}
export interface SvApproval {approvalNo:string;linkNo:string;activityNo:string;draftRevisionNo:string;draftHash:string;storeNo:string|null;scopeType?:'STORE'|'BRAND';scopeNo?:string;managementNo:string;status:string;policyHash:string;submittedBy:string;decidedBy:string|null;revision:number;reason:string|null;definition:SvActivityDefinition}
export interface SvActivityCheck {policy:SvEffectivePolicy;allowed:boolean;approvalRequired:boolean;blockers:string[]}
export interface SvGovCommand {managementNo:string;currency:CurrencyCode;expectedRevision:number}
export interface StoredValueGovernanceApi {
 mSvBrandActivityContext:StoredValueGovernanceApi["mSvActivityContext"];
 mSvBrandActivityPresets:StoredValueGovernanceApi["mSvActivityPresets"];
 mSvBrandActivitySchedule:StoredValueGovernanceApi["mSvActivitySchedule"];
 mSvBrandActivities:StoredValueGovernanceApi["mSvActivities"];
 mSvBrandActivity:StoredValueGovernanceApi["mSvActivity"];
 mSvBrandActivitySave:StoredValueGovernanceApi["mSvActivitySave"];
 mSvBrandActivityDraftEdit:StoredValueGovernanceApi["mSvActivityDraftEdit"];
 mSvBrandActivityCheck:StoredValueGovernanceApi["mSvActivityCheck"];
 mSvBrandActivitySubmit:StoredValueGovernanceApi["mSvActivitySubmit"];
 mSvBrandActivityStop:StoredValueGovernanceApi["mSvActivityStop"];
 mSvBrandActivityCopy:StoredValueGovernanceApi["mSvActivityCopy"];

 mSvActivityDraftEdit(store:string,no:string,body:{managementNo:string;definition:SvActivityDefinition;expectedRevision?:number},key:string):Promise<SvActivity>;
 mSvPreparePolicyPermissions(managementNo:string):Promise<{prepared:boolean}>;
 mSvActivityContext(store:string,q:{managementNo:string;currency:CurrencyCode;intent:'READ'|'MANAGE'}):Promise<SvGovernanceContext>;
 mSvActivityPresets(store:string,q:{managementNo:string;currency:CurrencyCode;page?:number;size?:number}):Promise<{records:Array<{templateCode:string;templateVersion:number;name:string;enabled:boolean;available:boolean;missingCapabilities:string[]}>;total:number}>;
 mSvActivitySchedule(store:string,q:{managementNo:string;currency:CurrencyCode;startDate:string;endDate:string}):Promise<{startAt:string;endAt:string;zone:string;currency:CurrencyCode;currencyScale:number}>;
 mSvGovernanceCapability():Promise<{protocol:number;enabled:boolean}>;
 mSvPolicyTargets(managementNo:string):Promise<SvPolicyTargetOption[]>;
 mSvPolicies(q:{managementNo:string;currency:CurrencyCode;targetType?:SvPolicyTarget;targetNo?:string}):Promise<SvPolicy[]>;
 mSvPolicyCreate(body:{managementNo:string;targetType:SvPolicyTarget;targetNo:string;currency:CurrencyCode;rules:SvPolicyRules},key:string):Promise<SvPolicy>;
 mSvPolicyEdit(no:string,body:{managementNo:string;edit:{rules:SvPolicyRules;expectedRevision:number}},key:string):Promise<SvPolicy>;
 mSvEffectivePolicy(store:string,q:{managementNo:string;currency:CurrencyCode}):Promise<SvEffectivePolicy>;
 mSvActivities(store:string,q:{managementNo:string;currency:CurrencyCode;status?:string;page?:number;size?:number}):Promise<{records:SvActivity[];total:number;page:number;size:number}>;
 mSvActivity(store:string,no:string,q:{managementNo:string;currency:CurrencyCode}):Promise<{activity:SvActivity;submission:SvSubmission|null}>;
 mSvActivitySave(store:string,no:string|null,body:{managementNo:string;definition:SvActivityDefinition;expectedRevision?:number},key:string):Promise<SvActivity>;
 mSvActivityCheck(store:string,no:string,body:SvGovCommand):Promise<SvActivityCheck>;
 mSvActivitySubmit(store:string,no:string,body:SvGovCommand,key:string):Promise<SvSubmission>;
 mSvActivityStop(store:string,no:string,body:SvGovCommand,key:string):Promise<SvSubmission>;
 mSvActivityCopy(store:string,no:string,body:SvGovCommand,key:string):Promise<SvActivity>;
 mSvApprovals(q:{managementNo:string;status?:string;cursor?:string;size?:number}):Promise<SvPage<SvApproval>>;
 mSvApproval(no:string,managementNo:string):Promise<SvApproval>;
 mSvApprovalDecide(no:string,body:{managementNo:string;decision:{decision:'APPROVE'|'RETURN';reason:string;expectedRevision:number}},key:string):Promise<SvApproval>;
}
