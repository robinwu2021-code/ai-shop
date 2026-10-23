package ai.neargo.shop.paybridge;

import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StoredValueMigrationReadinessServiceTest {
    final String sha="a".repeat(40),ledger="b".repeat(64);
    final Node node=new Node("pay-1","PAY_1","pay-svc",sha,"boot-pay");
    Inventory inventory(boolean sealed){return new Inventory("deploy-1",ledger,sealed,List.of(node));}
    @Test void unsealedInventoryDoesNotProbeWhileSealedInventoryUsesFreshChallenge(){
        var calls=new AtomicInteger();
        java.util.function.BiFunction<Node,Query,Capability> probe=(n,q)->{calls.incrementAndGet();return new Capability(q.challenge(),n.application(),"boot-pay",sha,1,true,true,ledger,true,Instant.now());};
        assertThat(new StoredValueMigrationReadinessService(inventory(false),probe).check().ready()).isFalse();assertThat(calls.get()).isZero();
        var service=new StoredValueMigrationReadinessService(inventory(true),probe);
        var first=service.check();var next=service.check();
        assertThat(first.ready()).isTrue();assertThat(next.ready()).isTrue();
        assertThat(first.nodes().getFirst().capability().challenge()).isNotEqualTo(next.nodes().getFirst().capability().challenge());
    }
    @Test void failedNodeDoesNotLeakTransportDetailsOrProduceSuccess(){
        var service=new StoredValueMigrationReadinessService(inventory(true),(n,q)->{throw new IllegalStateException("private URL/token detail");});
        var result=service.check();assertThat(result.ready()).isFalse();
        assertThat(result.nodes().getFirst().errorCode()).isEqualTo("PROBE_UNAVAILABLE");
        assertThat(result.toString()).doesNotContain("private URL/token detail");
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void serverInventoryCanBeSealedAndReplacedWithoutRestartingTheObservedProcess() throws Exception {
        var file=directory.resolve("inventory.json");var json=new tools.jackson.databind.ObjectMapper();var calls=new AtomicInteger();
        var service=new StoredValueMigrationReadinessService(()->StoredValueMigrationReadinessService.load(json,"",file.toString()),(n,q)->{calls.incrementAndGet();return new Capability(q.challenge(),n.application(),"boot-pay",sha,1,true,true,ledger,true,Instant.now());});
        assertThat(service.check().ready()).isFalse();assertThat(calls.get()).isZero();
        java.nio.file.Files.writeString(file,json.writeValueAsString(inventory(true)));
        assertThat(service.check().ready()).isTrue();
        var changed=new Inventory("deploy-2",ledger,true,List.of(new Node("pay-1","PAY_1","pay-svc",sha,"replacement-boot")));
        java.nio.file.Files.writeString(file,json.writeValueAsString(changed));
        assertThat(service.check().ready()).isFalse();
    }
    @Test void brokenConfiguredFileNeverFallsBackToAnOldSealedInlineInventory() throws Exception {
        var file=directory.resolve("inventory.json");java.nio.file.Files.writeString(file,"invalid-json");
        var json=new tools.jackson.databind.ObjectMapper();
        var loaded=StoredValueMigrationReadinessService.load(json,json.writeValueAsString(inventory(true)),file.toString());
        assertThat(loaded.sealed()).isFalse();assertThat(loaded.nodes()).isEmpty();
    }

    @Test void proofCarriesTheSingleInventorySnapshotActuallyUsedForProbes(){
        var reads=new AtomicInteger();
        var service=new StoredValueMigrationReadinessService(()->{reads.incrementAndGet();return inventory(true);},(n,q)->new Capability(q.challenge(),n.application(),"boot-pay",sha,1,true,true,ledger,true,Instant.now()));
        var proof=service.proof();assertThat(proof.report().ready()).isTrue();assertThat(reads.get()).isEqualTo(1);
        assertThat(proof.inventory()).isEqualTo(inventory(true));
        var cap=proof.report().nodes().getFirst().capability();
        assertThat(proof.report().inventoryHash()).isEqualTo(ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.check(proof.inventory(),proof.report().nodes(),cap.challenge(),proof.report().checkedAt()).inventoryHash());
    }

}
