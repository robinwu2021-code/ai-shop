package ai.neargo.shop.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Only the scanned credential and selected store are public input; identity comes from validated sessions. */
public record MemberResolveCommand(@NotBlank @Size(max = 64) String memberCode,
        @NotBlank @Size(max = 64) String storeNo) {
    @Override
    public String toString() {
        return "MemberResolveCommand[memberCode=REDACTED]";
    }
}
