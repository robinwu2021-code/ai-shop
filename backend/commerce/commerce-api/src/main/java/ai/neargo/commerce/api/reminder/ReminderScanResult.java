package ai.neargo.commerce.api.reminder;

import ai.neargo.commerce.api.common.*;

public record ReminderScanResult(String nextCursor, boolean hasMore, int accountsScanned, int candidatesCreated) {}
