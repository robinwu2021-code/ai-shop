package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintExecutionGuard(
        boolean allowed,
        GuardDecision decision,
        String currentBizRevision,
        boolean cancelled,
        String reason) {}
