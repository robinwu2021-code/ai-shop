package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintFactCursor(
        PrintScope scope,
        String sourceKind,
        Instant from,
        Instant to,
        Instant watermarkAt,
        String watermarkNo) {}
