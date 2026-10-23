package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintSubmission(
        String requestNo,
        List<String> batchNos,
        List<String> jobNos,
        String operationNo,
        List<String> exportNos) {
    public PrintSubmission {
        batchNos = List.copyOf(Objects.requireNonNull(batchNos, "batchNos"));
        jobNos = List.copyOf(Objects.requireNonNull(jobNos, "jobNos"));
        exportNos = List.copyOf(Objects.requireNonNull(exportNos, "exportNos"));
    }
}
