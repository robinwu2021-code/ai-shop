package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintDispatch(
        PrintScope scope,
        String jobNo,
        String attemptNo,
        String dispatchKey,
        String printerNo,
        long fenceEpoch,
        long connectionRevision,
        String credentialRef,
        String adapterCode,
        String providerScope,
        FrozenJson connectionSnapshot,
        PrintLayout layout) {}
