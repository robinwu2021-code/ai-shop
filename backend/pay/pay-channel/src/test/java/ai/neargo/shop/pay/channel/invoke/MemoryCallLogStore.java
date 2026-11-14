package ai.neargo.shop.pay.channel.invoke;

import ai.neargo.shop.pay.channel.entity.PayChannelCallLog;
import ai.neargo.shop.pay.channel.invoke.record.CallLogStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** 调用记录的内存实现：update 按非空字段合并，与 MyBatis updateById 的默认策略一致 */
final class MemoryCallLogStore implements CallLogStore {

    final List<PayChannelCallLog> rows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    boolean broken;

    @Override
    public synchronized Long insert(PayChannelCallLog row) {
        if (broken) {
            throw new IllegalStateException("db down");
        }
        row.setId(ids.incrementAndGet());
        rows.add(row);
        return row.getId();
    }

    @Override
    public synchronized void update(PayChannelCallLog patch) {
        if (broken) {
            throw new IllegalStateException("db down");
        }
        PayChannelCallLog r = rows.stream().filter(x -> x.getId().equals(patch.getId())).findFirst().orElseThrow();
        for (var f : PayChannelCallLog.class.getDeclaredFields()) {
            f.setAccessible(true);
            try {
                Object v = f.get(patch);
                if (v != null) {
                    f.set(r, v);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Override
    public synchronized Optional<PayChannelCallLog> lastSucceeded(String payChannel, String operation, String outNo) {
        return rows.stream()
                .filter(r -> payChannel.equals(r.getPayChannel()) && operation.equals(r.getOperation())
                        && Objects.equals(outNo, r.getOutTradeNo()) && "SUCCEEDED".equals(r.getOutcomeClass()))
                .max(Comparator.comparing(PayChannelCallLog::getId));
    }
}
