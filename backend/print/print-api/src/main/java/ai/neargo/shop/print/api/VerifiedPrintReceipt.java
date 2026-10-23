package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record VerifiedPrintReceipt(
        PrintScope scope,
        String sourceScope,
        String sourceReceiptKey,
        String attemptNo,
        ObservedPrintStatus status,
        PrintEvidenceLevel evidenceLevel,
        Long sourceSequence,
        Instant occurredAt,
        FrozenJson sanitizedEvidence) {}
