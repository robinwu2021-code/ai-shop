import type {StoredValueGovernanceApi} from "@/api/stored-value-governance";
/** Separate resource paths preserve brand permissions rather than broadening store roles. */
export function storedValueActivityApi(api:StoredValueGovernanceApi,brand:boolean){
 return {
  mSvActivityContext:brand?api.mSvBrandActivityContext:api.mSvActivityContext,
  mSvActivityPresets:brand?api.mSvBrandActivityPresets:api.mSvActivityPresets,
  mSvActivitySchedule:brand?api.mSvBrandActivitySchedule:api.mSvActivitySchedule,
  mSvActivities:brand?api.mSvBrandActivities:api.mSvActivities,
  mSvActivity:brand?api.mSvBrandActivity:api.mSvActivity,
  mSvActivitySave:brand?api.mSvBrandActivitySave:api.mSvActivitySave,
  mSvActivityDraftEdit:brand?api.mSvBrandActivityDraftEdit:api.mSvActivityDraftEdit,
  mSvActivityCheck:brand?api.mSvBrandActivityCheck:api.mSvActivityCheck,
  mSvActivitySubmit:brand?api.mSvBrandActivitySubmit:api.mSvActivitySubmit,
  mSvActivityStop:brand?api.mSvBrandActivityStop:api.mSvActivityStop,
  mSvActivityCopy:brand?api.mSvBrandActivityCopy:api.mSvActivityCopy,
 };
}
