package ai.neargo.shop.pay.storedvalue.migration;

import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Capability;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Query;


/** Stable business API; implementation keeps the original transaction and owner checks. */
public interface StoredValueWriterCapabilityService {
    static final String LEDGER_ID_SQL="SELECT SHA2(CONCAT(@@server_uuid,':',DATABASE()),256) AS ledger_fingerprint";

    static final String SCHEMA_SQL="SELECT COUNT(*) AS applied FROM flyway_schema_history WHERE version IN ('548','549','550') AND success=1";

    Capability capability(Query query);
}
