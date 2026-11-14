package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.CatalogContracts.CancelQueuedSyncReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SubmitSyncRunReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncRunItemVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncRunVO;
import ai.neargo.shop.common.PageData;

/** 目录能力沿原主体/门店范围；网络事实与本域事务分开，不默认通过来源或外部结果。 */
public interface SyncRunService {
    SyncRunVO submit(String entity, String store, String actor, SubmitSyncRunReq req, String key);

    SyncRunVO getRun(String entity, String store, String runNo);

    PageData<SyncRunVO> listRuns(
            String entity, String store, int page, int size, String connectionNo, String status);

    PageData<SyncRunItemVO> listItems(
            String entity, String store, String runNo, int page, int size);

    SyncRunVO cancelQueued(
            String entity,
            String store,
            String runNo,
            String actor,
            CancelQueuedSyncReq req,
            String key);
}
