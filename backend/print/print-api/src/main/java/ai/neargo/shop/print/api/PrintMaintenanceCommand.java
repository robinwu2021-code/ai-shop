package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintMaintenanceCommand(
        PrintScope scope, String sourceKind, Instant from, Instant to, int limit, boolean dryRun) {}
