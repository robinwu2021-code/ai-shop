package ai.neargo.commerce.api.reminder;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

public record ScanRun(String scanNo, Instant scanNow, String tenantNo, int shardNo, int shardCount, long upperAccountId) {}
