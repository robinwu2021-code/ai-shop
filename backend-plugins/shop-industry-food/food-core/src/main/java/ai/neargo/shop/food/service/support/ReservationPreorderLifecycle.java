package ai.neargo.shop.food.service.support;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.lifecycle.ServiceSlotLifecycle;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** Reservation owner holds the appointment lock before local draft/capacity release. */
@Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
public class ReservationPreorderLifecycle {
    private final FoodBusinessOperationsMapper mapper;
    private final ServiceSlotLifecycle slots;

    public ReservationPreorderLifecycle(
            FoodBusinessOperationsMapper mapper, ServiceSlotLifecycle slots) {
        this.mapper = mapper;
        this.slots = slots;
    }

    public void cancelled(
            String entity, String store, String reservation, String actor, String reason) {
        for (var row : mapper.reservationActivePreorders(entity, store, reservation)) {
            if (!Set.of("DRAFT", "READY").contains(row.get("status")))
                throw BizException.of(ErrorCode.CONFLICT);
            slots.releaseCart(entity, store, actor, row.text("preorder_no"));
            if (row.get("cart_no") != null)
                slots.releaseCart(entity, store, actor, row.text("cart_no"));
            if (mapper.cancelReservationPreorder(entity, row.text("preorder_no"), reason, actor)
                    != 1) throw BizException.of(ErrorCode.CONFLICT);
        }
    }
}
