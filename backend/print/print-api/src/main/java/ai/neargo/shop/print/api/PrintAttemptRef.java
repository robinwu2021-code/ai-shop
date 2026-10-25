package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintAttemptRef(
        PrintScope scope,
        String attemptNo,
        String providerScope,
        String providerJobId,
        String dispatchKey,
        String credentialRef,
        FrozenJson connectionSnapshot) {}
