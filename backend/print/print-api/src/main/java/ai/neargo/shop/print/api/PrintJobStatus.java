package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public enum PrintJobStatus {
    PENDING,
    DISPATCHING,
    ACCEPTED,
    PRINTING,
    COMPLETED,
    RETRY_WAIT,
    UNKNOWN,
    FAILED,
    CANCELLED,
    STALE
}
