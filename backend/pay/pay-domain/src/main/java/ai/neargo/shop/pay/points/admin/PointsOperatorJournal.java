package ai.neargo.shop.pay.points.admin;

import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.policy.PointsDigests;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsFactStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.function.Supplier;

/** Commits an operator intent and its metadata outcome in the same pay transaction. */
@Service
public class PointsOperatorJournal {
    private final PointsConfigurationStore configuration;
    private final PointsFactStore facts;
    private final ObjectMapper json = new ObjectMapper();

    public PointsOperatorJournal(PointsConfigurationStore configuration, PointsFactStore facts) {
        this.configuration = configuration;
        this.facts = facts;
    }

    @Transactional("payTxManager")
    public OperatorResult apply(
            String operation,
            String requestId,
            String type,
            String actor,
            String reason,
            Object request,
            Supplier<String> work) {
        if (requestId == null
                || requestId.isBlank()
                || requestId.length() > 120
                || type == null
                || type.isBlank()
                || actor == null
                || actor.isBlank()
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException("POINTS_OPERATOR_INPUT_INVALID");
        }
        configuration.lockTypeAndPool(type);
        String key = PointsKeys.position("OC", operation, requestId);
        String body = json.writeValueAsString(new Intent(operation, request, actor, reason));
        var prior = facts.lockInbox(key);
        if (prior != null) {
            var receipt = json.readValue((String) prior.get("payload_json"), Receipt.class);
            if (!body.equals(receipt.body()))
                throw new IllegalArgumentException("POINTS_OPERATOR_REQUEST_CONFLICT");
            return receipt.result();
        }
        String reference = work.get();
        var result = new OperatorResult(requestId, operation, type, reference, Clock.systemUTC().millis());
        String payload = json.writeValueAsString(new Receipt(body, result));
        facts.recordEvent(type, key, PointsDigests.sha256(payload), payload, "OPS_APPLIED");
        return result;
    }

    public record OperatorResult(
            String requestId, String operation, String typeCode, String reference, long at) {}

    public record Intent(String operation, Object request, String actor, String reason) {}

    public record Receipt(String body, OperatorResult result) {}
}
