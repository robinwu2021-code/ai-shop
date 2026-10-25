package ai.neargo.shop.spi.storedvalue;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope;
import java.time.Instant;
import java.util.Objects;
/** Private Core/Pay authorization boundary, never a public request body. No channel credentials are returned. */
public final class StoredValueCollections {
 private StoredValueCollections(){}
 public record Requirement(ExecutionScope scope,String issuerNo,String issuerVersionNo,String settlementEntityNo,boolean required){public Requirement{Objects.requireNonNull(scope);ContractChecks.id(issuerNo);ContractChecks.id(issuerVersionNo);ContractChecks.id(settlementEntityNo);}}
 public record Lookup(Requirement requirement){}
 public record Proof(Requirement requirement,String authenticatedActorKey,long authorityRevision,Instant expiresAt){public Proof{Objects.requireNonNull(requirement);ContractChecks.require(authenticatedActorKey!=null&&authenticatedActorKey.matches("(USER|MCH):[A-Za-z0-9_-]{1,55}")&&authorityRevision>=0&&expiresAt!=null);}}
 public record Options(ExecutionScope scope,Proof proof){public Options{Objects.requireNonNull(scope);}}
}
