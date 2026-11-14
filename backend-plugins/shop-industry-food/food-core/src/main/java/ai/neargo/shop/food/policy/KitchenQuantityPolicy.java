package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.entity.FnbKitchenTask;

import java.math.BigDecimal;
import java.util.Set;

/** Cumulative started/ready/served counters never decrease; loss is an immutable separate fact. */
public final class KitchenQuantityPolicy {
    private KitchenQuantityPolicy() {}

    public static void positive(BigDecimal qty) {
        if (qty == null
                || qty.signum() <= 0
                || qty.scale() > 6
                || qty.precision() - qty.scale() > 12) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    public static void apply(
            FnbKitchenTask task, String action, BigDecimal delta, BigDecimal loss) {
        apply(task, action, delta, loss, loss);
    }

    public static void apply(
            FnbKitchenTask task,
            String action,
            BigDecimal delta,
            BigDecimal loss,
            BigDecimal readyLoss) {
        positive(delta);
        if (action == null
                || loss == null
                || loss.signum() < 0
                || readyLoss == null
                || readyLoss.signum() < 0
                || readyLoss.compareTo(loss) > 0) throw conflict();
        BigDecimal makingLoss = loss.subtract(readyLoss);
        if (!Set.of("START", "READY", "SERVE", "HOLD", "FIRE", "URGE", "VOID", "LOSS")
                .contains(action)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (Set.of("SERVED", "VOIDED").contains(task.getStatus())) throw conflict();
        BigDecimal available = task.getQty().subtract(task.getVoidQty());
        switch (action) {
            case "START" -> {
                if ("HELD".equals(task.getStatus())) throw conflict();
                if (task.getMakingQty().add(delta).compareTo(available) > 0) throw conflict();
                task.setMakingQty(task.getMakingQty().add(delta));
            }
            case "READY" -> {
                if ("HELD".equals(task.getStatus())
                        || task.getReadyQty()
                                        .add(delta)
                                        .compareTo(task.getMakingQty().subtract(makingLoss))
                                > 0) throw conflict();
                task.setReadyQty(task.getReadyQty().add(delta));
            }
            case "SERVE" -> {
                if (task.getServedQty().add(delta).compareTo(task.getReadyQty().subtract(readyLoss))
                        > 0) throw conflict();
                task.setServedQty(task.getServedQty().add(delta));
            }
            case "VOID" -> {
                // Only unstarted quantity can be voided; prepared food requires an explicit loss
                // fact.
                if (task.getVoidQty().add(delta).add(task.getMakingQty()).compareTo(task.getQty())
                        > 0) throw conflict();
                task.setVoidQty(task.getVoidQty().add(delta));
            }
            case "LOSS" -> {
                if (loss.add(delta).add(task.getServedQty()).compareTo(task.getMakingQty()) > 0)
                    throw conflict();
                var disposedReady =
                        delta.min(
                                task.getReadyQty()
                                        .subtract(task.getServedQty())
                                        .subtract(readyLoss));
                readyLoss = readyLoss.add(disposedReady);
                loss = loss.add(delta);
                makingLoss = loss.subtract(readyLoss);
            }
            case "HOLD" -> {
                if (!"QUEUED".equals(task.getStatus()) || task.getMakingQty().signum() != 0)
                    throw conflict();
                task.setStatus("HELD");
                return;
            }
            case "FIRE" -> {
                if (!"HELD".equals(task.getStatus())) throw conflict();
                task.setStatus("QUEUED");
                return;
            }
            case "URGE" -> {
                // An urge is a service fact, never additional demand or a release from hold.
                if (delta.compareTo(task.getQty()) > 0) throw conflict();
                return;
            }
            default -> throw conflict();
        }
        if (task.getVoidQty().compareTo(task.getQty()) == 0) task.setStatus("VOIDED");
        else if (task.getServedQty().compareTo(available) == 0) task.setStatus("SERVED");
        else if (task.getServedQty().add(loss).compareTo(available) == 0) task.setStatus("VOIDED");
        else if (task.getReadyQty().add(makingLoss).compareTo(available) == 0)
            task.setStatus("READY");
        else if (task.getMakingQty().signum() > 0) task.setStatus("MAKING");
        else if (!"HELD".equals(task.getStatus())) task.setStatus("QUEUED");
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
