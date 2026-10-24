package ai.neargo.shop.trade.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import ai.neargo.shop.trade.service.PricingSeedService.ImportMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Closed HTTP contracts exclude trusted identity, provider capabilities and tax-review claims. */
public final class PricingConfigRequests {
    private PricingConfigRequests() { }

    public record Create(@NotBlank @Pattern(regexp = "FOOD|RETAIL|BEAUTY_SERVICE") String industryCode,
            @NotBlank @Size(max = 32) String templateCode, @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 65536) String definition) {
        @JsonAnySetter
        public void unknown(String field, Object value) { rejectUnknown(); }
    }

    public record Save(
            @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) @Min(0) long expectedDraftVersion,
            @NotBlank @Size(max = 65536) String content,
            @Size(max = 32) String templateRevisionNo) {
        @JsonAnySetter
        public void unknown(String field, Object value) { rejectUnknown(); }
    }

    public record Preview(
            @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) @Min(0) long expectedDraftVersion,
            @Size(max = 32) String rollbackFromRevisionNo) {
        @JsonAnySetter
        public void unknown(String field, Object value) { rejectUnknown(); }
    }

    public record Publish(
            @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) @Min(0) long expectedDraftVersion,
            @Size(max = 32) String expectedActiveRevisionNo,
            @NotBlank @Size(max = 64) String previewHash, @NotBlank @Size(max = 256) String reason,
            @Size(max = 32) String rollbackFromRevisionNo) {
        @JsonAnySetter
        public void unknown(String field, Object value) { rejectUnknown(); }
    }

    public record Initialize(@NotBlank @Size(max = 32) String templateRevisionNo,
            @NotBlank @Size(max = 256) String reason, @Size(max = 65536) String initialOverride) {
        public Initialize(String templateRevisionNo, String reason) { this(templateRevisionNo, reason, null); }
        @JsonAnySetter
        public void unknown(String field, Object value) { rejectUnknown(); }
    }

    public record Reset(
            @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) @Min(0) long expectedDraftVersion,
            @NotEmpty @Size(max = 64) List<@NotBlank String> paths) {
        @JsonAnySetter
        public void unknown(String field, Object value) { rejectUnknown(); }
    }

    public record Upgrade(
            @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) @Min(0) long expectedDraftVersion,
            @NotBlank @Size(max = 32) String templateRevisionNo) {
        @JsonAnySetter
        public void unknown(String field, Object value) { rejectUnknown(); }
    }

    public record Seed(@NotBlank @Size(max = 65536) String seed, @NotNull ImportMode mode) {
        @JsonAnySetter
        public void unknown(String field, Object value) { rejectUnknown(); }
    }

    private static void rejectUnknown() { throw new IllegalArgumentException("Unknown pricing request field"); }
}
