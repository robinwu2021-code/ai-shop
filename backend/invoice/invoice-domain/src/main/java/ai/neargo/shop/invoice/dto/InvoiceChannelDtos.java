package ai.neargo.shop.invoice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public final class InvoiceChannelDtos {
    private InvoiceChannelDtos() {}

    public record SubmitCommand(
            @NotBlank @Size(max = 16) String regimeCode,
            @NotBlank @Size(max = 32) String channelCode,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 256) String> materialRefs) {}

    public record ReviseCommand(
            @NotNull @Positive Integer expectedRevision,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 256) String> materialRefs) {}

    public record ReviewCommand(
            @NotBlank @Size(max = 64) String entityNo,
            @NotNull @Positive Integer expectedRevision,
            @NotNull @Pattern(regexp = "APPROVED|NEEDS_INFO|REJECTED") String decision,
            @NotBlank @Size(max = 256) String reason) {}

    public record BindCommand(
            @NotBlank @Size(max = 64) String entityNo,
            @NotNull @Positive Integer expectedRevision,
            @Valid ChannelCredentials credentials) {}

    public record ChannelCredentials(
            @Size(max = 64) String accountCode,
            @NotBlank @Size(max = 128) String user,
            @NotBlank @Size(max = 256) String password,
            @Pattern(regexp = "[A-Fa-f0-9]{32}") String qrAesKeyHex) {
        @Override public String toString() { return "ChannelCredentials[redacted]"; }
    }

    public record ActivateCommand(
            @NotBlank @Size(max = 64) String entityNo,
            @NotBlank @Size(max = 64) String accountNo,
            @NotNull @PositiveOrZero Long expectedRouteVersion,
            @NotBlank @Size(max = 32) String rangeNo,
            @NotBlank @Size(max = 256) String reason) {}

    public record ApplicationView(
            String applicationNo, String entityNo, String regimeCode, String channelCode, String environment,
            String sellerTaxId, String status, int currentRevision, String accountNo, String createdBy,
            LocalDateTime updatedAt) {}

    public record RevisionView(int revisionNo, List<String> materialRefs, String submittedBy, LocalDateTime submittedAt) {}

    public record ApplicationEvent(
            int revisionNo, String action, String toStatus, String actorNo, String reason, LocalDateTime occurredAt) {}

    public record ApplicationDetail(
            ApplicationView application, List<RevisionView> revisions, List<ApplicationEvent> events) {}

    public record AccountView(
            String accountNo, String entityNo, String regimeCode, String channelCode, String environment,
            String sellerTaxId, long currentRevision, LocalDateTime verifiedAt) {}

    public record RouteView(
            String entityNo, String regimeCode, String environment, String activeAccountNo, long routeVersion,
            LocalDateTime updatedAt) {}
}
