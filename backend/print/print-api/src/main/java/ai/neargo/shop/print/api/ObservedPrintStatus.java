package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public enum ObservedPrintStatus {
    RECEIVED,
    SUBMITTING,
    ACCEPTED,
    PRINTING,
    CONFIRMED,
    NOT_ACCEPTED,
    UNKNOWN,
    FAILED,
    CANCELLED
}
