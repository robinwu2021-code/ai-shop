package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintMaintenanceReport(
        int scanned, int changed, int blocked, int failed, boolean hasMore, String summary) {}
