package ai.neargo.commerce.api.reminder;

import ai.neargo.commerce.api.common.*;

public record ReminderScanCommand(ScanRun run, String afterCursor, int limit) {}
