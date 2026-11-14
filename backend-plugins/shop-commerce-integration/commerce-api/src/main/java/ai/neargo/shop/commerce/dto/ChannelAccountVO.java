package ai.neargo.shop.commerce.dto;

/** 不携带物理主键、Token或credentialRef。 */
public record ChannelAccountVO(
        String accountNo,
        String profileNo,
        String externalAccountRef,
        String status,
        long version) {}
