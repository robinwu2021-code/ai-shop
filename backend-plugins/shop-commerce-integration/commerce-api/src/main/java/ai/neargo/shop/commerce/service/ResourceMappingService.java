package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.MappingContracts.CreateResourceMappingReq;
import ai.neargo.shop.commerce.dto.MappingContracts.MappingRevisionVO;
import ai.neargo.shop.commerce.dto.MappingContracts.ResourceMappingVO;
import ai.neargo.shop.commerce.dto.MappingContracts.ReviseResourceMappingReq;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;

/** 映射写入只追加草稿和历史修订；公开接口不能授予订单导入权威。 */
public interface ResourceMappingService {
    ResourceMappingVO createMapping(
            String entity, String store, String actor, CreateResourceMappingReq req, String key);

    ResourceMappingVO reviseMapping(
            String entity,
            String store,
            String mappingNo,
            String actor,
            ReviseResourceMappingReq req,
            String key);

    PageData<ResourceMappingVO> listMappings(
            String entity,
            String store,
            int page,
            int size,
            String connectionNo,
            ObjectType objectType,
            String externalRef,
            String sourceRef);

    PageData<MappingRevisionVO> listRevisions(
            String entity, String store, String mappingNo, int page, int size);
}
