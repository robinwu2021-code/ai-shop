package ai.neargo.shop.commerce.dto;

/** 产品协议状态与品牌启用状态分开，不回传内部能力配置。 */
public record ProductProfileVO(
        String profileNo,
        String providerNo,
        String productCode,
        String market,
        String protocolCode,
        String status) {}
