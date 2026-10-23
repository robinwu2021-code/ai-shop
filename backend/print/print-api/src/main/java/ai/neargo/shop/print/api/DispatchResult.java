package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record DispatchResult(
        DispatchOutcome outcome, String providerJobId, FrozenJson evidence, String errorKey) {}
