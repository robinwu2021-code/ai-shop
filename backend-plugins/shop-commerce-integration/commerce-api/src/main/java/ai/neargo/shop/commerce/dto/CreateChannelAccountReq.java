package ai.neargo.shop.commerce.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

/** 主体由可信上下文给定；公开请求不接收密钥或凭据引用。 */
public record CreateChannelAccountReq(
        @JsonDeserialize(using = CommerceTextDeserializer.class) @NotBlank @Size(max = 32)
                String profileNo,
        @JsonDeserialize(using = CommerceTextDeserializer.class) @NotBlank @Size(max = 128)
                String externalAccountRef) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown account field");
    }
}
