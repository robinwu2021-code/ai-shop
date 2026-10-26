package ai.neargo.shop.commerce.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

/** 账号来自受控路径，不允许传入凭据或任意跳转地址。 */
public record RevokeChannelAuthorizationReq(
        @JsonDeserialize(using = CommerceVersionDeserializer.class) @NotNull @PositiveOrZero
                Long version,
        @JsonDeserialize(using = CommerceTextDeserializer.class) @NotBlank @Size(max = 64)
                String reasonCode,
        @JsonDeserialize(using = CommerceTextDeserializer.class) @NotBlank @Size(max = 256)
                String reason) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown authorization field");
    }
}
