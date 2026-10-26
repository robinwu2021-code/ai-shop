package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.CatalogContracts.CatalogScopeVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.CreateCatalogScopeReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SaveCatalogSourcesReq;
import ai.neargo.shop.common.PageData;

/** 目录能力沿原主体/门店范围；网络事实与本域事务分开，不默认通过来源或外部结果。 */
public interface CatalogScopeService {
    CatalogScopeVO createScope(
            String entity, String store, String actor, CreateCatalogScopeReq req, String key);

    CatalogScopeVO getScope(String entity, String store, String scopeNo);

    PageData<CatalogScopeVO> listScopes(
            String entity, String store, int page, int size, String connectionNo, String status);

    CatalogScopeVO saveSources(
            String entity,
            String store,
            String scopeNo,
            String actor,
            SaveCatalogSourcesReq req,
            String key);
}
