package ai.neargo.commerce.api.port;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

public record NotificationCommand(String noticeNo, String deduplicationKey, StoreRef store, String entitlementCode, Instant coverageEndAt, String threshold, NotificationRecipient recipient, String templateCode) {}
