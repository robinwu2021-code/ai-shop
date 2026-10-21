package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.ListConfigRevisionsQuery;
import ai.neargo.shop.food.dto.RepublishConfigReq;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.PublishConfigResultVO;
import java.time.OffsetDateTime;

/** Resolves store/time-scoped immutable definitions instead of trusting a mutable shared-head pointer. */
public interface ConfigRevisionService {
    default PublishConfigResultVO republishRevision(String entity, String store, String revision, String actor,
            RepublishConfigReq req, String key) { throw BizException.of(ErrorCode.NOT_FOUND); }
    /** Older standalone providers reject newly introduced kinds explicitly. */
    default PublishConfigResultVO publishMenu(String entity, String store, String number, String actor,
            PublishConfigReq req, String key) {
        throw BizException.of(ErrorCode.NOT_FOUND);
    }
    default PublishConfigResultVO publishStation(String entity, String store, String number, String actor,
            PublishConfigReq req, String key) {
        throw BizException.of(ErrorCode.NOT_FOUND);
    }

    PublishConfigResultVO publishRecipe(String entityNo, String storeNo, String configNo, String actorNo,
            PublishConfigReq req, String idempotencyKey);
    PublishConfigResultVO publishBundle(String entityNo, String storeNo, String configNo, String actorNo,
            PublishConfigReq req, String idempotencyKey);
    PublishConfigResultVO publishModifier(String entityNo, String storeNo, String configNo, String actorNo,
            PublishConfigReq req, String idempotencyKey);
    PublishConfigResultVO publishOptionGroup(String entityNo, String storeNo, String configNo, String actorNo,
            PublishConfigReq req, String idempotencyKey);
    PageData<ConfigRevisionVO> listRevisions(String entityNo, String storeNo, ListConfigRevisionsQuery query);
    ConfigRevisionContentVO getRevision(String entityNo, String storeNo, String revisionNo);
    ConfigRevisionContentVO resolveRevision(String entityNo, String storeNo, String kind, String configNo,
            OffsetDateTime asOf);
}
