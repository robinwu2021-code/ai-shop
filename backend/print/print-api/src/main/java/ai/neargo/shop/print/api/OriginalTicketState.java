package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record OriginalTicketState(
        String jobNo, long revision, PrintJobStatus status, Instant observedAt) {}
