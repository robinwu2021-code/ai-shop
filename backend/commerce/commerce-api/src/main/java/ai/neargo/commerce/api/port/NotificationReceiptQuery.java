package ai.neargo.commerce.api.port;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record NotificationReceiptQuery(String noticeNo, String deduplicationKey, NotificationRecipient recipient, Optional<String> providerMessageNo) {}
