package ai.neargo.shop.food.channel.port;
import ai.neargo.shop.spi.food.FoodProductionStockPort;
import ai.neargo.shop.spi.food.FoodProductionStockInternalApi;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import java.util.function.Supplier;
public final class CoreFoodProductionStockPortImpl implements FoodProductionStockPort {
    private final Supplier<FoodProductionStockInternalApi> api;
    public CoreFoodProductionStockPortImpl(Supplier<FoodProductionStockInternalApi> api){this.api=api;}
    @Override public String owner(InventoryProductionPort.Command command){return api.get().owner(command);}
    @Override public InventoryProductionPort.Receipt consume(InventoryProductionPort.Command command){return api.get().consume(command);}
    @Override public InventoryProductionPort.Receipt reverse(InventoryProductionPort.Reverse command){return api.get().reverse(command);}
    @Override public InventoryProductionPort.Receipt query(InventoryProductionPort.Command command){return api.get().query(command);}
}
