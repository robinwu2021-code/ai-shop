package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.food.dto.FoodKdsContracts.ReplayVO;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.FoodKdsStreamService;

import jakarta.annotation.PreDestroy;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Streams actual persisted event batches. Every batch reauthorizes; no queue snapshot is
 * synthesized as an event.
 */
public class FoodKdsStreamServiceImpl implements FoodKdsStreamService {
    private final FoodKdsService service;
    private final ScheduledExecutorService ticker =
            Executors.newScheduledThreadPool(
                    2,
                    r -> {
                        var t = new Thread(r, "food-kds-stream");
                        t.setDaemon(true);
                        return t;
                    });
    private final Set<SseEmitter> clients = ConcurrentHashMap.newKeySet();

    public FoodKdsStreamServiceImpl(FoodKdsService service) {
        this.service = service;
    }

    @Override
    public SseEmitter subscribe(
            String entity, String store, String actor, String subscription, long cursor) {
        ReplayVO first = service.replay(entity, store, actor, subscription, cursor, 100);
        var emitter = new SseEmitter(1800000L);
        clients.add(emitter);
        var stopped = new AtomicBoolean();
        final long[] after = {cursor};
        final int[] tick = {0};
        final ScheduledFuture<?>[] future = {null};
        Runnable close =
                () -> {
                    stopped.set(true);
                    clients.remove(emitter);
                    if (future[0] != null) {
                        future[0].cancel(false);
                    }
                };
        emitter.onCompletion(close);
        emitter.onError(e -> close.run());
        emitter.onTimeout(
                () -> {
                    close.run();
                    emitter.complete();
                });
        Consumer<ReplayVO> send =
                page -> {
                    try {
                        for (var event : page.events()) {
                            emitter.send(
                                    SseEmitter.event()
                                            .id(Long.toString(event.cursor()))
                                            .name(event.eventType())
                                            .data(event, MediaType.APPLICATION_JSON));
                        }
                        after[0] = page.nextCursor();
                        emitter.send(
                                SseEmitter.event()
                                        .id(Long.toString(after[0]))
                                        .name("cursor")
                                        .data(after[0]));
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                };
        try {
            send.accept(first);
        } catch (RuntimeException failure) {
            close.run();
            emitter.completeWithError(failure);
            return emitter;
        }
        future[0] =
                ticker.scheduleWithFixedDelay(
                        () -> {
                            if (stopped.get()) {
                                return;
                            }
                            try {
                                var page =
                                        service.replay(
                                                entity, store, actor, subscription, after[0], 100);
                                if (page.nextCursor() != after[0]) {
                                    send.accept(page);
                                } else if (++tick[0] % 10 == 0) {
                                    emitter.send(SseEmitter.event().comment("heartbeat"));
                                }
                            } catch (RuntimeException | IOException failure) {
                                close.run();
                                emitter.completeWithError(failure);
                            }
                        },
                        1,
                        2,
                        TimeUnit.SECONDS);
        if (stopped.get()) {
            future[0].cancel(false);
        }
        return emitter;
    }

    @PreDestroy
    public void shutdown() {
        ticker.shutdownNow();
        clients.forEach(SseEmitter::complete);
        clients.clear();
    }
}
