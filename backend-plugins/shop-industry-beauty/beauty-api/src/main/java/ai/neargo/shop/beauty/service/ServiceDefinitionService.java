package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.ServiceDefinitionQuery;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVO;
import ai.neargo.shop.beauty.dto.ServiceDefinitionVersionVO;
import ai.neargo.shop.common.PageData;

/** Defines immutable execution rules for existing shared products, not a beauty-owned product catalog. */
public interface ServiceDefinitionService {
    /** Child requirements are returned only through an authorized entity/store parent. */
    PageData<ServiceDefinitionVO> listDefinitions(String entityNo, String currentStoreNo, String actorNo,
            ServiceDefinitionQuery query);
    /** Publishes a version, its requirements and critical-action evidence atomically. */
    ServiceDefinitionVersionVO publishDefinitionVersion(String entityNo, String currentStoreNo, String actorNo,
            PublishServiceDefinitionVersionReq req, String idempotencyKey);
}
