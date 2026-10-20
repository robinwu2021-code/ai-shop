package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import java.nio.charset.StandardCharsets;

/** Trusted internal source evidence only. An export does not authorize import, cutover, or financial writes. */
public interface LegacyCouponFrozenSnapshotPort {
    int MAX_PAYLOAD_BYTES=8*1024*1024;
    Snapshot exportFrozen(EntitlementContext context,Query query);

    record Query(SourceIdentity source,String batchNo,long frozenRevision,String watermark) {
        public Query {
            ContractChecks.present(source); ContractChecks.id(batchNo); ContractChecks.hash(watermark);
            ContractChecks.require(source.sourceSystem()!=SourceSystem.PAY && source.sourceKind()==SourceKind.TEMPLATE
                    && frozenRevision>=0);
        }
    }
    /** Payload preserves historical source facts, including old codes: never log or expose it through public DTOs. */
    record Snapshot(int schemaVersion,SourceIdentity source,String entityNo,String batchNo,long frozenRevision,
                    String watermark,String contentHash,String payload) {
        public Snapshot {
            ContractChecks.require(schemaVersion==1);
            new Query(source,batchNo,frozenRevision,watermark);
            ContractChecks.id(entityNo); ContractChecks.hash(contentHash);
            ContractChecks.require(payload!=null && !payload.isBlank()
                    && payload.getBytes(StandardCharsets.UTF_8).length<=MAX_PAYLOAD_BYTES);
        }
        @Override public String toString() {
            return "FrozenLegacySnapshot[source="+source+", batchNo="+batchNo+", payload=<redacted>]";
        }
    }
}
