package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public enum GuardDecision {
    ALLOW_HISTORICAL_RECEIPT,
    CANCEL_UNSUBMITTED_EXECUTION,
    REQUIRE_VOID,
    HOLD_UNKNOWN
}
