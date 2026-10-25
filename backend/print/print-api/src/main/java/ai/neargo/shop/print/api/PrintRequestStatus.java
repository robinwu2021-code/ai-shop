package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public enum PrintRequestStatus {
    PENDING,
    BLOCKED,
    GENERATED,
    CANCELLED,
    SKIPPED
}
