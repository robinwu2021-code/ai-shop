package ai.neargo.shop.pay.entitlement.event;

import ai.neargo.shop.pay.entitlement.entity.PayEntInbox;
import ai.neargo.shop.pay.mapper.entitlement.PayEntInboxMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Applies a trusted inbound event once, in the same pay transaction as its business effect. */
@Component
public class PayEntitlementInboxProcessor {
    private final PayEntInboxMapper inbox;
    private final ObjectMapper json;

    public PayEntitlementInboxProcessor(PayEntInboxMapper inbox, ObjectMapper json) {
        this.inbox = inbox;
        this.json = json;
    }

    @Transactional(transactionManager = "payTxManager", propagation = Propagation.MANDATORY)
    public boolean process(String tenantNo, String consumer, String eventNo, Object payload, Runnable action) {
        if (!id(tenantNo) || !id(consumer) || !id(eventNo) || payload == null || action == null)
            throw new IllegalArgumentException("Pay inbox identity and action are required");
        String payloadHash = sha256(json.writeValueAsString(payload));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        var row = new PayEntInbox();
        row.setTenantNo(tenantNo);
        row.setConsumer(consumer);
        row.setEventNo(eventNo);
        row.setPayloadHash(payloadHash);
        row.setProcessedAt(now);
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy("SYSTEM");
        row.setUpdatedBy("SYSTEM");
        int inserted = inbox.insertIfAbsent(row);
        PayEntInbox persisted = inbox.lock(tenantNo, consumer, eventNo);
        if (persisted == null) throw new IllegalStateException("Pay inbox claim disappeared");
        if (!payloadHash.equals(persisted.getPayloadHash()))
            throw new IllegalStateException("Pay inbox event payload conflict");
        if (inserted == 0) return false;
        action.run();
        return true;
    }

    private static boolean id(String value) {
        return value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.strip());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }
}
