package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.CatalogContracts.SaveSyncPolicyReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncPolicyVO;

/** 目录能力沿原主体/门店范围；网络事实与本域事务分开，不默认通过来源或外部结果。 */
public interface SyncPolicyService {
    SyncPolicyVO getPolicy(String entity, String store, String scopeNo);

    SyncPolicyVO savePolicy(
            String entity,
            String store,
            String scopeNo,
            String actor,
            SaveSyncPolicyReq req,
            String key);
}
