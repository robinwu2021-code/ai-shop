package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.PublishConfigResultVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ListConfigRevisionsQuery;
import java.time.OffsetDateTime;

/** Resolves store/time-scoped immutable definitions instead of trusting a mutable shared-head pointer. */
public interface ConfigRevisionService {
    PublishConfigResultVO publishModifier(String entityNo, String storeNo, String configNo, String actorNo,
            PublishConfigReq req, String idempotencyKey);
    PublishConfigResultVO publishOptionGroup(String entityNo, String storeNo, String configNo, String actorNo,
            PublishConfigReq req, String idempotencyKey);
    PageData<ConfigRevisionVO> listRevisions(String entityNo, String storeNo, ListConfigRevisionsQuery query);
    ConfigRevisionContentVO getRevision(String entityNo, String storeNo, String revisionNo);
    ConfigRevisionContentVO resolveRevision(String entityNo, String storeNo, String kind, String configNo,
            OffsetDateTime asOf);
}
