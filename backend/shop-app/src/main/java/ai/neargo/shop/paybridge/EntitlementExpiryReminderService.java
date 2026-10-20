package ai.neargo.shop.paybridge;

import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.neargo.shop.payclient.EntitlementLifecycleAppService;
import org.springframework.stereotype.Service;

/** Sends inbox reminders only; it never expires or deducts any asset. */
@Service
public class EntitlementExpiryReminderService {
    private static final Logger LOG = LoggerFactory.getLogger(EntitlementExpiryReminderService.class);
    private final EntitlementLifecycleAppService pay;
    private final MessageService messages;
    public EntitlementExpiryReminderService(EntitlementLifecycleAppService pay, MessageService messages) {
        this.pay = pay; this.messages = messages;
    }
    public Result run(long afterId, int limit) {
        var context = new EntitlementContext("MAIN", "EXPIRY_REMINDER", "EXPIRY_REMINDER", "0".repeat(64));
        // Pay read finishes before notification transactions; there is no held financial lock here.
        var page = pay.expiringSoon(context, afterId, limit);
        int failed = 0;
        for (var row : page.records()) {
            try {
                messages.push(row.userNo(), MessageService.TRADE, "权益即将到期",
                        "您的权益即将到期，请在会员权益中查看有效期与使用记录。", null,
                        key(context.tenantNo(), row.instanceNo(), row.effectiveUntil().toString()));
            } catch (RuntimeException failure) {
                failed++;
                LOG.warn("Expiry reminder deferred for instance {}", row.instanceNo());
            }
        }
        return new Result(page.records().size(), failed, page.nextId());
    }
    private static String key(String tenant, String instance, String until) {
        try {
            return "ER" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((tenant + ":" + instance + ":" + until).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
    }
    public record Result(int scanned, int failed, long nextId) { }
}
