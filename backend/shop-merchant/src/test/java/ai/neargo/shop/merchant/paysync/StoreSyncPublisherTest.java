package ai.neargo.shop.merchant.paysync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader.Changed;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader.Cursor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** review 2026-11-06：水位卡死、全量同步空转、时区口径、分布式锁。 */
class StoreSyncPublisherTest {

    static final LocalDateTime T = LocalDateTime.of(2026, 11, 6, 8, 0, 0);

    static Changed row(long id, LocalDateTime at) {
        return new Changed(id, "ST" + id, "E1", at, StoreSnapshotReader.millis(at));
    }

    @Test
    void timerWaitsForApplicationReadyAndStopsWithContext() {
        var reader = mock(StoreSnapshotReader.class);
        var legacy = mock(OutboxEventBus.class);
        var facade = mock(ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade.class);
        var publisher = new StoreSyncPublisher(reader, legacy, NearPayStoreSyncTest.PROPS,
                (LockingTaskExecutor) null);
        publisher.setUnified(facade);
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context, "shop.nearpay.store-sync.enabled=true");
        context.registerBean(StoreSyncPublisher.class, () -> publisher);
        try {
            context.refresh();
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(publisher, "timer")).isNull();
            org.mockito.Mockito.verifyNoInteractions(reader, legacy, facade);

            when(facade.enabled("PLATFORM")).thenReturn(true);
            context.publishEvent(new org.springframework.boot.context.event.ApplicationReadyEvent(
                    new org.springframework.boot.SpringApplication(), new String[0], context,
                    java.time.Duration.ZERO));
            var timer = (java.util.concurrent.ScheduledExecutorService)
                    org.springframework.test.util.ReflectionTestUtils.getField(publisher, "timer");
            assertThat(timer).isNotNull();
            assertThat(timer.isShutdown()).isFalse();
            publisher.enqueue(List.of(row(7, T)), null);
            verify(facade).accept(org.mockito.ArgumentMatchers.eq("PLATFORM"), any());
            org.mockito.Mockito.verifyNoInteractions(legacy);
            context.close();
            assertThat(timer.isShutdown()).isTrue();
        } finally {
            context.close();
        }
    }

    @Test
    void unifiedStoreSyncUsesStableIngressWithoutLegacyRows() {
        var facade=mock(ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade.class);
        when(facade.enabled("PLATFORM")).thenReturn(true);
        var legacy=mock(OutboxEventBus.class);
        var p=new StoreSyncPublisher(mock(StoreSnapshotReader.class),legacy,NearPayStoreSyncTest.PROPS,(LockingTaskExecutor)null);
        p.setUnified(facade);var row=row(7,T);p.enqueue(List.of(row),null);
        var fresh=new StoreSyncPublisher(mock(StoreSnapshotReader.class),legacy,NearPayStoreSyncTest.PROPS,(LockingTaskExecutor)null);
        fresh.setUnified(facade);fresh.enqueue(List.of(row),null);
        var events=ArgumentCaptor.forClass(ai.neargo.shop.event.outbox.OutboxEnvelope.class);
        verify(facade,org.mockito.Mockito.times(2)).accept(org.mockito.ArgumentMatchers.eq("PLATFORM"),events.capture());
        assertThat(events.getAllValues().get(0)).isEqualTo(events.getAllValues().get(1));
        assertThat(events.getValue().sourceSystem()).isEqualTo("MERCHANT_STORE_SYNC");
        assertThat(events.getValue().occurredAt()).isEqualTo(java.time.Instant.ofEpochMilli(row.revision()));
        org.mockito.Mockito.verifyNoInteractions(legacy);
    }

    @Test
    @DisplayName("★★★ 同一秒内超过一批（主体改名波及 500+ 门店）：游标按 (变更时间, 门店 id) 前进，不会原地打转")
    void fullBatchAtSameInstantAdvancesByCompositeCursor() {
        var reader = mock(StoreSnapshotReader.class);
        var first = IntStream.rangeClosed(1, 500).mapToObj(i -> row(i, T)).toList();
        var second = IntStream.rangeClosed(501, 620).mapToObj(i -> row(i, T)).toList();
        var asked = new ArrayList<Cursor>();
        when(reader.dbNow()).thenReturn(T.plusMinutes(1));
        when(reader.changedAfter(any(), anyInt())).thenAnswer(a -> {
            Cursor c = a.getArgument(0);
            asked.add(c);
            return c.id() < 500 ? first : second;
        });
        var p = new StoreSyncPublisher(reader, mock(OutboxEventBus.class), NearPayStoreSyncTest.PROPS, (LockingTaskExecutor) null);
        p.scan();
        p.scan();
        assertThat(asked.get(1)).isEqualTo(new Cursor(T, 500));
    }

    @Test
    @DisplayName("★★ 追平之后回看 5 秒（晚提交的事务），游标取数据库里的时间，不用本机时钟")
    void caughtUpRewindsFiveSecondsFromDatabaseTime() {
        var reader = mock(StoreSnapshotReader.class);
        when(reader.dbNow()).thenReturn(T);
        when(reader.changedAfter(any(), anyInt())).thenReturn(List.of(row(7, T.minusSeconds(30))));
        var p = new StoreSyncPublisher(reader, mock(OutboxEventBus.class), NearPayStoreSyncTest.PROPS, (LockingTaskExecutor) null);
        p.scan();
        assertThat(p.cursor()).isEqualTo(new Cursor(T.minusSeconds(35), 0));
        // 首次扫描从数据库时间往前一天开始
        var fresh = new StoreSyncPublisher(reader, mock(OutboxEventBus.class), NearPayStoreSyncTest.PROPS, (LockingTaskExecutor) null);
        var c = ArgumentCaptor.forClass(Cursor.class);
        fresh.scan();
        verify(reader, org.mockito.Mockito.atLeastOnce()).changedAfter(c.capture(), anyInt());
        assertThat(c.getAllValues()).contains(new Cursor(T.minusDays(1), 0));
    }

    @Test
    @DisplayName("★★★ 运营全量同步：每次请求都用新事件号，已同步过的门店也会真正重推")
    void fullSyncUsesFreshEventNumbersPerRequest() {
        var reader = mock(StoreSnapshotReader.class);
        var outbox = mock(OutboxEventBus.class);
        when(reader.all(any())).thenReturn(List.of(row(1, T), row(2, T)));
        var p = new StoreSyncPublisher(reader, outbox, NearPayStoreSyncTest.PROPS, (LockingTaskExecutor) null);
        assertThat(p.publishAll(List.of())).isEqualTo(2);
        assertThat(p.publishAll(List.of())).isEqualTo(2);
        var nos = ArgumentCaptor.forClass(String.class);
        verify(outbox, org.mockito.Mockito.times(4)).publish(nos.capture(), any());
        assertThat(nos.getAllValues()).doesNotHaveDuplicates();
        assertThat(nos.getAllValues()).allMatch(n -> n.contains(":FULL:") && n.length() <= 64);
        String longNo = "NPS:" + "S".repeat(64) + ":1762300000000:FULL:abcdef123456";
        assertThat(StoreSyncPublisher.fit(longNo)).hasSize(64).isEqualTo(StoreSyncPublisher.fit(longNo));
    }

    @Test
    @DisplayName("★★ 追平后回看窗口反复读到同一家店的同一修订号：不再入队、不再计数（166 每分钟「scanned 1 store change」误报）")
    void lookbackRereadOfSameRevisionIsNotRequeued() {
        var reader = mock(StoreSnapshotReader.class);
        var outbox = mock(OutboxEventBus.class);
        when(reader.dbNow()).thenReturn(T);
        when(reader.changedAfter(any(), anyInt())).thenReturn(List.of(row(7, T.minusSeconds(2))));
        var p = new StoreSyncPublisher(reader, outbox, NearPayStoreSyncTest.PROPS, (LockingTaskExecutor) null);
        assertThat(p.scanCount()).isEqualTo(1);
        assertThat(p.scanCount()).isZero();
        assertThat(p.scanCount()).isZero();
        verify(outbox, org.mockito.Mockito.times(1)).publish(any(), any());
        // 同一家店有了新修订号，照常入队
        when(reader.changedAfter(any(), anyInt())).thenReturn(List.of(row(7, T.minusSeconds(1))));
        assertThat(p.scanCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 定时扫描在分布式锁里跑：多实例只有拿到锁的那个扫描")
    void timedScanRunsUnderDistributedLock() throws Throwable {
        var locks = mock(LockingTaskExecutor.class);
        var p = new StoreSyncPublisher(mock(StoreSnapshotReader.class), mock(OutboxEventBus.class), NearPayStoreSyncTest.PROPS, locks);
        p.tick();
        var cfg = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(locks).executeWithLock(any(Runnable.class), cfg.capture());
        assertThat(cfg.getValue().getName()).isEqualTo("nearpay-store-sync");
        assertThat(cfg.getValue().getLockAtMostFor()).isLessThan(NearPayStoreSyncTest.PROPS.scanInterval());
    }
}
