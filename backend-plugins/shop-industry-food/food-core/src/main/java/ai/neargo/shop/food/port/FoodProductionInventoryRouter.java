package ai.neargo.shop.food.port;

import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.food.FoodProductionStockPort;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import java.util.function.Supplier;

/** Per-original kitchen event routing, constructed locally instead of registered as a legacy SPI. */
public final class FoodProductionInventoryRouter implements InventoryProductionPort {
    private final Supplier<FoodProductionStockPort> owners;private final Supplier<InventoryProductionPort> legacy;
    public FoodProductionInventoryRouter(Supplier<FoodProductionStockPort> owners,Supplier<InventoryProductionPort> legacy){this.owners=owners;this.legacy=legacy;}
    @Override public Receipt consume(Command command){var owner=owner(command);return "FOOD_LOCAL".equals(owner)?required().consume(command):original().consume(command);}
    @Override public Receipt query(Command command){var owner=owner(command);return "FOOD_LOCAL".equals(owner)?required().query(command):original().query(command);}
    @Override public Receipt reverse(Reverse command){if(command==null)throw BizException.of(ErrorCode.BAD_REQUEST);
        // A textual reversal reason is not evidence that already prepared materials are resalable.
        if("FOOD_LOCAL".equals(owner(command.original())))return required().reverse(command);
        if(command.disposition()!=null)throw unsupported();
        return original().reverse(command);}
    public void validateDisposition(Command command,String disposition,String reasonCode,String actor) {
        if("FOOD_LOCAL".equals(owner(command))){
            if(!java.util.Set.of("RESALE","LOSS").contains(disposition==null?"":disposition)||reasonCode==null||reasonCode.isBlank()
                    ||reasonCode.length()>32||actor==null||actor.isBlank()||actor.length()>32)
                throw BizException.of(ErrorCode.BAD_REQUEST);
        }else if(disposition!=null)throw unsupported();
    }
    private String owner(Command command){String owner=required().owner(command);if(!"FOOD_LOCAL".equals(owner)&&!"LEGACY_INVENTORY".equals(owner))throw unsupported();return owner;}
    private FoodProductionStockPort required(){var p=owners.get();if(p==null)throw unsupported();return p;}
    private InventoryProductionPort original(){var p=legacy.get();if(p==null)throw unsupported();return p;}
    private static BizException unsupported(){return BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
}
