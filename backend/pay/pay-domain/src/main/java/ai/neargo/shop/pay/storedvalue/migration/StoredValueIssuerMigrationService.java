package ai.neargo.shop.pay.storedvalue.migration;

import java.util.List;





/** Stable business API; implementation keeps the original transaction and owner checks. */
public interface StoredValueIssuerMigrationService {
    public record StoreMapping(String storeNo,String entityNo,String issuerNo) { }

    public record IdentityEvidence(String customerNo,String userNo) { }

    public record Batch(String tenantNo,String actorNo,List<StoreMapping> stores,List<IdentityEvidence> identities) {
        public Batch { stores=List.copyOf(stores); identities=List.copyOf(identities); }
    }

    public record Issue(String code,String accountNo) { }

    public record AccountMapping(String accountNo,String issuerNo,String customerNo,String currency,
            long principalMinor,long bonusMinor,long heldPrincipalMinor,long heldBonusMinor,long accountSequence) { }

    public record Report(boolean ready,String fingerprint,List<AccountMapping> accounts,List<Issue> issues) {
        public Report { accounts=List.copyOf(accounts); issues=List.copyOf(issues); }
    }

    public record MappingResult(String state,long accountCount,String auditedFingerprint) { }

    Report audit(Batch batch);

    MappingResult map(Batch batch,String expectedFingerprint,String commandKey);

    MappingResult mapFenced(StoredValueMigrationManifestService.Evidence evidence,String expectedFingerprint,String commandKey);
}
