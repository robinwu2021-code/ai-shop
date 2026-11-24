package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public enum ExportSourceType {
    JOB,
    BATCH,
    SIMULATION,
    INVOICE_PROOF,
    INVOICE_DETAIL;

    public boolean isInvoiceDocument() {
        return this == INVOICE_PROOF || this == INVOICE_DETAIL;
    }
}
