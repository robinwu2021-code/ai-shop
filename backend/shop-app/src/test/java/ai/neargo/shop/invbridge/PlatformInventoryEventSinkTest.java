package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 进销存回写由库存库发件箱直接发出（清单 §〇 C3）：沿用 {@code inv_outbox} 的事件号（加 {@code INV-} 命名空间），
 * 重投同一条得到同一个号，名单内类型直发中间件，否则落平台发件箱时按号去重；不再每投一次新发一个号。
 */
@DisplayName("进销存回写按原事件号转发")
class PlatformInventoryEventSinkTest {

    private final OutboxEventBus bus = mock(OutboxEventBus.class);
    private final PlatformInventoryEventSink sink = new PlatformInventoryEventSink(bus);

    @Test
    @DisplayName("重投同一条库存事件，转发出去的是同一个事件号")
    void redeliveryKeepsTheInventoryEventNo() {
        assertThat(sink.deliver("EVT001", "OWNER-1", "POSTED", "{}")).isTrue();
        assertThat(sink.deliver("EVT001", "OWNER-1", "POSTED", "{}")).isTrue();

        verify(bus, times(2)).forward(eq("INV-EVT001"), argThat((DomainEvent e) ->
                "INV_POSTED".equals(e.eventType()) && "OWNER-1".equals(e.aggregateId())));
        verify(bus, never()).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("转发失败返回 false，留在库存发件箱里退避重投")
    void failureStaysInInventoryOutbox() {
        doThrow(new IllegalStateException("down")).when(bus).forward(any(), any());
        assertThat(sink.deliver("EVT002", "OWNER-1", "POSTED", "{}")).isFalse();
    }

    @Test
    @DisplayName("内嵌部署由平台在库存提交后接手（R10.3），统一模式不接手")
    void takesOverUnlessUnified() {
        assertThat(sink.takesOverAfterCommit()).isTrue();
        var unified = mock(UnifiedOutboxFacade.class);
        when(unified.enabled("PLATFORM")).thenReturn(true);
        sink.setUnified(unified);
        assertThat(sink.takesOverAfterCommit()).isFalse();
    }
}
