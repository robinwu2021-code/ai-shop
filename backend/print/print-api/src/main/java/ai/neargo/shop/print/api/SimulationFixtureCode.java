package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public enum SimulationFixtureCode {
    SUCCESS,
    NOT_ACCEPTED,
    OFFLINE,
    PAPER_OUT,
    ACCEPTED_NO_RESULT,
    TIMEOUT_UNKNOWN,
    DUPLICATE_RECEIPT,
    LATE_RECEIPT,
    PARTIAL_COPIES
}
