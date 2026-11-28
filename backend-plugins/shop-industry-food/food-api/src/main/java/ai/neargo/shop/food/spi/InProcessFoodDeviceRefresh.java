package ai.neargo.shop.food.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 只在本进程内传递的刷新信号：提交后交给本进程的监听者。没配消息中间件时用它；
 * 配了中间件时由子类改为经广播订阅发给每个实例（{@link #publish}）。
 */
public class InProcessFoodDeviceRefresh implements FoodDeviceRefresh {
    private static final Logger log = LoggerFactory.getLogger(InProcessFoodDeviceRefresh.class);
    private final CopyOnWriteArrayList<Consumer<Signal>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public final void signal(Signal signal) {
        if (signal == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            send(signal);
                        }
                    });
        } else {
            send(signal);
        }
    }

    private void send(Signal signal) {
        try {
            publish(signal);
        } catch (RuntimeException failure) {
            log.warn("[food] 设备刷新信号发送失败，本进程内照发：{}", failure.toString());
            deliver(signal);
        }
    }

    /** 发出一条信号；默认直接交给本进程的监听者。 */
    protected void publish(Signal signal) {
        deliver(signal);
    }

    /** 交给本进程的每个监听者；某个监听者失败不影响别的。 */
    protected final void deliver(Signal signal) {
        for (var listener : listeners) {
            try {
                listener.accept(signal);
            } catch (RuntimeException failure) {
                log.debug("[food] 设备刷新监听者失败：{}", failure.toString());
            }
        }
    }

    @Override
    public final AutoCloseable listen(Consumer<Signal> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
