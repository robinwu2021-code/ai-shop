package ai.neargo.commerce.api.reminder;

import ai.neargo.commerce.api.common.*;

public record ReminderRetryCommand(String noticeNo, String operationNo, String reason) {}
