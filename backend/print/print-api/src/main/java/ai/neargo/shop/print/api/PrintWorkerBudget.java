package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintWorkerBudget(int maxItems, Duration timeBudget, String workerNo) {
    public PrintWorkerBudget {
        if (maxItems < 1
                || maxItems > 500
                || timeBudget == null
                || timeBudget.isNegative()
                || timeBudget.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("budget");
    }
}
