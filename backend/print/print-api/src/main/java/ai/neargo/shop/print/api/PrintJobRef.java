package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintJobRef(
        String tenantNo, String entityNo, String storeNo, String jobNo, Long expectedRevision) {
    public PrintJobRef {
        if (tenantNo == null || tenantNo.isBlank() || tenantNo.length() > 32)
            throw new IllegalArgumentException("tenantNo");
        if (entityNo == null || entityNo.isBlank() || entityNo.length() > 32)
            throw new IllegalArgumentException("entityNo");
        if (storeNo == null || storeNo.isBlank() || storeNo.length() > 32)
            throw new IllegalArgumentException("storeNo");
    }
}
