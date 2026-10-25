package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintOperationError(
        String key, boolean retryable, String traceNo, FrozenJson conflicts) {}
