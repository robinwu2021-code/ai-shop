package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

public record NotificationRecipient(String recipientType, String recipientNo, String channel) {}
