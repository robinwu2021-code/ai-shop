package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateBundleReq;
import ai.neargo.shop.food.dto.CreateBundleResultVO;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.dto.BundleMutationVO;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.GetBundleVO;
import ai.neargo.shop.food.dto.ListBundlesQuery;

/** Extends original SKU identity with restaurant composition, never another product or stock ledger. */
public interface BundleService {
    CreateBundleResultVO createBundle(String entityNo, String storeNo, String actorNo,
            CreateBundleReq req, String idempotencyKey);
    BundleMutationVO replaceDefinition(String entityNo, String storeNo, String skuNo, String actorNo,
            ReplaceBundleDefinitionReq req, String idempotencyKey);
    GetBundleVO getBundle(String entityNo, String storeNo, String skuNo);
    PageData<BundleConfigVO> listBundles(String entityNo, String storeNo, ListBundlesQuery query);
}
