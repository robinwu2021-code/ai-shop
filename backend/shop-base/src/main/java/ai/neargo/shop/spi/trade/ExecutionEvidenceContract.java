package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.product.StockDispositionContract;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.TreeMap;

/** New neutral domains preserve original binding bytes and leave all V1 source hashes untouched. */
public final class ExecutionEvidenceContract {
    private ExecutionEvidenceContract() {
    }

    /** Every record field except its own hash and observedAt participates, including schema and nested hashes. */
    public static String digest(String domain, Object record, String ownHash) {
        Map<String, Object> values = new TreeMap<>();
        try {
            for (RecordComponent field : record.getClass().getRecordComponents()) {
                if (!field.getName().equals(ownHash) && !field.getName().equals("observedAt")) {
                    values.put(field.getName(), field.getAccessor().invoke(record));
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("invalid neutral record", failure);
        }
        return hash(domain, values);
    }

    /** Domain-separated exact UTF-8 stored bytes, never a parsed and reserialized binding. */
    public static String bindingBytesHash(String stored) {
        return StockDispositionContract.hash("SERVICE_EXECUTION_ORIGINAL_BYTES_V1\n" + stored);
    }

    /** Canonical neutral values use new domains; no legacy hash changes. */
    public static String hash(String domain, Object value) {
        return StockDispositionContract.hash(domain + "\n" + ServiceExecutionContract.canonical(value));
    }

    /** Public neutral validation supports product consumers without exposing manual source payload parsing. */
    public static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("invalid execution original evidence");
    }

    public static void hashValue(String value) {
        ServiceExecutionContract.hash(value);
    }

    public static void reference(String value, int max) {
        ServiceExecutionContract.reference(value, max);
    }
}
