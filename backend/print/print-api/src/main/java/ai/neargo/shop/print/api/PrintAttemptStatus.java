package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public enum PrintAttemptStatus {
    PREPARED,
    SUBMITTING,
    ACCEPTED,
    PRINTING,
    CONFIRMED,
    NOT_ACCEPTED,
    UNKNOWN,
    FAILED,
    CANCELLED
}
