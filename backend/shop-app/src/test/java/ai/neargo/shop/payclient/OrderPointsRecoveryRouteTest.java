package ai.neargo.shop.payclient;
import ai.neargo.shop.pay.points.recon.impl.PointsSourceReconcilerImpl;
import ai.neargo.shop.pay.points.store.PointsCursorStore;
import ai.neargo.shop.spi.points.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class OrderPointsRecoveryRouteTest {
    @Test void source_recovery_uses_versioned_delivery_instead_of_duplicate_legacy_enqueue() {
        var source=mock(SourceFactsPort.class);var facts=mock(PointsFactPort.class);var cursors=mock(PointsCursorStore.class);
        var command=mock(PointsEarnPort.GrantCommand.class);when(command.typeCode()).thenReturn("P");
        var fact=new SourceFactsPort.Fact(1,null,command,null,null,null);
        when(source.scan("P",0,20)).thenReturn(new SourceFactsPort.Page(1,List.of(fact)));when(source.deliver(fact)).thenReturn(true);
        var factory=new StaticListableBeanFactory();factory.addBean("source",source);
        var scan=new PointsSourceReconcilerImpl(factory.getBeanProvider(SourceFactsPort.class),facts,cursors);
        assertThat(scan.scan("P",20).watermark()).isEqualTo(1);
        verify(source).deliver(fact);verifyNoInteractions(facts);verify(cursors).advance(eq("POINTS_SOURCE:P"),eq("P"),eq(0L),eq(1L),anyString());
    }
}
