package ai.neargo.shop.food.api.stream;

import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.spi.FoodDeviceRefresh;
import ai.neargo.shop.food.spi.FoodDeviceRefresh.Signal;

import jakarta.annotation.PreDestroy;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 屏的刷新通知流（TDD-餐饮域迁移新库 §2.8 D-3）。只推 {@code refresh} 事件（变化类型 + 该看的工位），
 * 不推业务数据；屏收到后按工位拉后厨任务列表、排号列表。
 *
 * <ul>
 *   <li>连上先核验订阅并推一次全量刷新（断线重连就是一次补齐）；</li>
 *   <li>收到本店、涉及这台屏工位的信号就推；</li>
 *   <li>每 {@value #FALLBACK_SECONDS} 秒重新核验订阅并兜底推一次全量刷新：漏掉的信号最多晚这么久补上，
 *       订阅被吊销或过期的屏在这时断开。</li>
 * </ul>
 */
public class FoodKdsStreamTransportImpl implements FoodKdsStreamTransport {
    static final long FALLBACK_SECONDS = 30;
    static final String ALL = "ALL";
    private final FoodKdsService service;
    private final FoodDeviceRefresh refresh;
    private final long fallbackSeconds;
    private final ScheduledExecutorService ticker =
            Executors.newScheduledThreadPool(
                    1,
                    r -> {
                        var t = new Thread(r, "food-kds-stream");
                        t.setDaemon(true);
                        return t;
                    });
    private final Set<SseEmitter> clients = ConcurrentHashMap.newKeySet();

    public FoodKdsStreamTransportImpl(FoodKdsService service, FoodDeviceRefresh refresh) {
        this(service, refresh, FALLBACK_SECONDS);
    }

    /** @param fallbackSeconds 兜底刷新与重新核验的间隔（测试用较短的值） */
    public FoodKdsStreamTransportImpl(FoodKdsService service, FoodDeviceRefresh refresh, long fallbackSeconds) {
        this.service = service;
        this.refresh = refresh;
        this.fallbackSeconds = fallbackSeconds;
    }

    @Override
    public SseEmitter subscribe(String entity, String store, String actor, String device) {
        var screen = service.authorize(entity, store, actor, device);
        List<String> stations = screen.stationNos();
        // 厨显收后厨与工位的变化，叫号屏只收排号的变化（TDD §2.8 D-5）
        Set<String> kinds =
                "CALL_SCREEN".equals(screen.deviceType())
                        ? Set.of(FoodDeviceRefresh.QUEUE)
                        : Set.of(FoodDeviceRefresh.KITCHEN, FoodDeviceRefresh.STATION);
        var emitter = new SseEmitter(1800000L);
        clients.add(emitter);
        var stopped = new AtomicBoolean();
        final AutoCloseable[] listening = {null};
        final ScheduledFuture<?>[] future = {null};
        Runnable close =
                () -> {
                    if (!stopped.compareAndSet(false, true)) return;
                    clients.remove(emitter);
                    if (future[0] != null) future[0].cancel(false);
                    try {
                        if (listening[0] != null) listening[0].close();
                    } catch (Exception ignored) {
                        // 取消监听不会失败
                    }
                };
        emitter.onCompletion(close);
        emitter.onError(e -> close.run());
        emitter.onTimeout(
                () -> {
                    close.run();
                    emitter.complete();
                });
        try {
            send(emitter, ALL, stations);
        } catch (IOException failure) {
            close.run();
            emitter.completeWithError(failure);
            return emitter;
        }
        listening[0] =
                refresh.listen(
                        signal -> {
                            if (stopped.get()
                                    || !kinds.contains(signal.kind())
                                    || !signal.concerns(entity, store, stations)) return;
                            try {
                                send(emitter, signal.kind(), watched(signal, stations));
                            } catch (IOException | RuntimeException failure) {
                                close.run();
                                emitter.completeWithError(failure);
                            }
                        });
        future[0] =
                ticker.scheduleWithFixedDelay(
                        () -> {
                            if (stopped.get()) return;
                            try {
                                service.authorize(entity, store, actor, device);
                                send(emitter, ALL, stations);
                            } catch (IOException | RuntimeException failure) {
                                close.run();
                                emitter.completeWithError(failure);
                            }
                        },
                        fallbackSeconds,
                        fallbackSeconds,
                        TimeUnit.SECONDS);
        if (stopped.get()) future[0].cancel(false);
        return emitter;
    }

    /** 信号涉及的、这台屏在看的工位；整店信号即这台屏的全部工位。 */
    private static List<String> watched(Signal signal, List<String> stations) {
        return signal.stationNos().isEmpty()
                ? stations
                : signal.stationNos().stream().filter(stations::contains).toList();
    }

    private static void send(SseEmitter emitter, String kind, List<String> stations)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("stationNos", stations);
        synchronized (emitter) {
            emitter.send(SseEmitter.event().name("refresh").data(body, MediaType.APPLICATION_JSON));
        }
    }

    @PreDestroy
    public void shutdown() {
        ticker.shutdownNow();
        clients.forEach(SseEmitter::complete);
        clients.clear();
    }
}
