package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

/** 连接的稳定业务身份；不把授权配置或数据库实体直接序列化。 */
public record ChannelConnectionVO(
        String connectionNo,
        String storeNo,
        String accountNo,
        String profileNo,
        String externalStoreRef,
        String status,
        Owner sourceOwner,
        String fulfillmentProfile,
        String revisionNo,
        long version) {}
