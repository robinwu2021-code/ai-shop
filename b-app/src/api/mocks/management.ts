import type {ManagementApi} from '../management-types';
const unavailable=async():Promise<never>=>{throw new Error('management.notEnabled')};
export const managementMock:ManagementApi={
 mManagementContext:async()=>({actorKey:'',spaces:[],canBootstrap:false,bootstrapSources:[]}),
 mCreateManagementSpace: unavailable,
 mManagementResources: unavailable,
 mManagementGrant: unavailable,
 mRevokeManagementGrant: unavailable,
 mManagementEntities: unavailable,
 mManagementStores: unavailable,
 mPaymentMerchantCandidates: unavailable,
 mBrands: unavailable,
 mCreateBrand: unavailable,
 mBrand: unavailable,
 mEditBrand: unavailable,
 mBrandStatus: unavailable,
 mBrandStores: unavailable,
 mBrandAudit: unavailable,
 mStoreOperatingContext: unavailable,
 mCheckBrandChange: unavailable,
 mSubmitBrandChange: unavailable,
 mBrandChange: unavailable,
 mDecideBrandChange: unavailable,
};
