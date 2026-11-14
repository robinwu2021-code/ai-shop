package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

/** 仅草稿路由配置；不能以此授权远端接单或本地履约。 */
public record CreateChannelConnectionReq(
        @JsonDeserialize(using = CommerceTextDeserializer.class) @NotBlank @Size(max = 32)
                String accountNo,
        @JsonDeserialize(using = CommerceTextDeserializer.class) @NotBlank @Size(max = 128)
                String externalStoreRef,
        @JsonDeserialize(using = CommerceOwnerDeserializer.class) @NotNull Owner sourceOwner,
        @JsonDeserialize(using = CommerceTextDeserializer.class) @NotBlank @Size(max = 32)
                String fulfillmentProfile) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown connection field");
    }
}
