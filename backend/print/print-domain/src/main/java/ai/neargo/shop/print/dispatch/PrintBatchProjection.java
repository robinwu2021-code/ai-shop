package ai.neargo.shop.print.dispatch;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.dispatch.PrintStateModels.*;

import java.util.*;

public final class PrintBatchProjection {
    public BatchCounts calculate(List<PrintJobSnapshot> jobs) {
        var required = jobs.stream().filter(PrintJobSnapshot::required).toList();
        int completed = 0, failed = 0, unknown = 0, cancelled = 0, resolved = 0;
        for (var j : required) {
            if (j.status() == PrintJobStatus.COMPLETED) completed++;
            else if (j.replacementResolved()) resolved++;
            if (j.status() == PrintJobStatus.FAILED) failed++;
            if (j.status() == PrintJobStatus.UNKNOWN) unknown++;
            if (j.status() == PrintJobStatus.CANCELLED) cancelled++;
        }
        var status =
                !required.isEmpty() && completed == required.size()
                        ? PrintBatchStatus.COMPLETED
                        : unknown + failed > 0
                                ? PrintBatchStatus.ATTENTION
                                : completed > 0
                                        ? PrintBatchStatus.PARTIAL
                                        : PrintBatchStatus.PENDING;
        return new BatchCounts(
                required.size(), completed, failed, unknown, cancelled, resolved, status);
    }
}
