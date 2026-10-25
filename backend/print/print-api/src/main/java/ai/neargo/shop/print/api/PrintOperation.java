package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintOperation(
        String operationNo,
        String type,
        PrintOperationStatus status,
        String targetType,
        String targetNo,
        FrozenJson result,
        PrintOperationError error,
        Instant createdAt,
        Instant finishedAt) {}
