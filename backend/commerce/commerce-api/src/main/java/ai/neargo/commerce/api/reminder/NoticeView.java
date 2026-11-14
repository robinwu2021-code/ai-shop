package ai.neargo.commerce.api.reminder;

import java.time.Instant;
import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record NoticeView(String noticeNo, StoreRef store, String entitlementCode, Instant coverageEndAt, String threshold, String recipientType, String recipientNo, String channel, NoticeStatus status, int attempts, Optional<Instant> nextAttemptAt, Optional<String> providerMessageNo) {}
