package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public enum PrintBatchStatus {
    PENDING,
    PROCESSING,
    PARTIAL,
    ATTENTION,
    COMPLETED,
    CANCELLED
}
