package ai.neargo.commerce.api.port;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

/** Recovery retains the trusted store identity from the original notification command. */
public record NotificationReceiptQuery(String noticeNo, String deduplicationKey, StoreRef store, NotificationRecipient recipient, Optional<String> providerMessageNo) {}
