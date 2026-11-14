package ai.neargo.shop.spi.resource;

import ai.neargo.shop.idem.IdempotencyScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Original owner-journal evidence for all industries; neither current hold state nor a source permission. */
public interface ResourceCommandFactsPort {
    /** Caller holds no transaction; unknown commands are explicit absence, transport failure is never absence. */
    Lookup lookup(ResourceSourceAuthorityPort.Request request);

    /** An explicit wrapper distinguishes a successful absent read from an empty/failed HTTP response. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Lookup(@JsonProperty(value = "command", required = true) Fact command) { }
    enum Status { ACCEPTED, SUCCEEDED, REJECTED }

    /** Raw results stay on trusted internal links; callers must not log or expose these private bytes. */
    record Fact(ResourceSourceAuthorityPort.Binding binding, Status status, long revision,
            String resultPayload, String rejectionCode, String terminalHash) {
        public Fact {
            if (binding == null || status == null || revision < 0) {
                throw new IllegalArgumentException("bound command state required");
            }
            if (status == Status.ACCEPTED) {
                if (resultPayload != null || rejectionCode != null || terminalHash != null) {
                    throw new IllegalArgumentException("pending command cannot carry a terminal result");
                }
            } else {
                if (resultPayload == null || resultPayload.isBlank()
                        || resultPayload.getBytes(StandardCharsets.UTF_8).length > 65536
                        || status == Status.SUCCEEDED && rejectionCode != null
                        || status == Status.REJECTED && (rejectionCode == null || rejectionCode.isBlank()
                            || rejectionCode.length() > 64 || !rejectionCode.equals(rejectionCode.strip()))) {
                    throw new IllegalArgumentException("complete original terminal evidence required");
                }
                if (!Objects.equals(terminalHash,
                        ResourceCommandFactsPort.fingerprint(binding, status, revision,
                                resultPayload, rejectionCode))) {
                    throw new IllegalArgumentException("terminal evidence fingerprint differs");
                }
            }
        }
    }

    /** Stable byte-sensitive digest, not a signature or authority when supplied by an untrusted caller. */
    static String fingerprint(ResourceSourceAuthorityPort.Binding b, Status status, long revision,
            String resultPayload, String rejectionCode) {
        return IdempotencyScope.requestHash("RESOURCE_COMMAND_FACT_V1", b.tenantNo(), b.entityNo(), b.storeNo(),
                b.commandNo(), b.sourceType(), b.sourceNo(), Long.toString(b.sourceRevision()), b.action(),
                b.commandHash(), b.planHash(), status.name(), Long.toString(revision), resultPayload,
                rejectionCode == null ? "" : rejectionCode);
    }
}
