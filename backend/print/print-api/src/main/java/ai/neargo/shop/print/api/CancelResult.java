package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record CancelResult(
        ObservedPrintStatus observedStatus,
        boolean queueTerminated,
        boolean partialOutput,
        FrozenJson evidence) {}
