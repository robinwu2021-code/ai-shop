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
}
