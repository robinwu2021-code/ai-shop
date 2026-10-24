package ai.neargo.shop.promotion.service;

import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.entity.PmtActivity;

/** Original P5 business contract; implementation retains its authorization and transaction boundaries. */
public interface BrandActivityOwnershipService {
    /** Verify the immutable management and brand owner marker before exposing an activity. */
    boolean visible(ActivityActor actor,PmtActivity row);

    /** Join the existing Core activity transaction when recording its brand owner. */
    void register(ActivityActor actor,String activity);
}
