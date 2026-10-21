package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateMenuReq;
import ai.neargo.shop.food.dto.CreateMenuResultVO;
import ai.neargo.shop.food.dto.ListMenusQuery;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuResultVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.UpdateMenuReq;
import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.food.service.MenuService;
import ai.neargo.shop.food.spi.MenuInternalApi;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Supplier;

/** One typed FOOD call; malformed remote successes cannot become authoritative menu metadata. */
public class RemoteMenuService implements MenuService {
    private final MenuInternalApi api;
    public RemoteMenuService(MenuInternalApi api) { this.api = api; }

    @Override
    public CreateMenuResultVO createMenu(String entity, String store, String actor, CreateMenuReq req, String key) {
        context(entity, store, actor, key); MenuDefinitionPolicy.create(req);
        var result = call(() -> api.createMenu(new MenuInternalApi.CreateCommand(entity, store, actor, key, req)));
        if (result == null || !MaterialCatalogFacts.validKey(result.configNo(), 32)
                || result.version() == null || result.version() != 0 || !"DRAFT".equals(result.status())) {
            throw new IllegalStateException("Invalid menu creation receipt");
        }
        return result;
    }

    @Override
    public MenuResultVO updateMenu(String entity, String store, String menu, String actor, UpdateMenuReq req,
            String key) {
        context(entity, store, actor, key); MenuDefinitionPolicy.key(menu); MenuDefinitionPolicy.update(req);
        var result = call(() -> api.updateMenu(
                new MenuInternalApi.UpdateCommand(entity, store, menu, actor, key, req)));
        var v = result == null ? null : result.menu();
        if (!MenuDefinitionPolicy.valid(v, store) || !menu.equals(v.menuNo()) || v.version() != req.version() + 1
                || !"DRAFT".equals(v.status()) || !req.name().equals(v.name())
                || !req.menuType().equals(v.menuType()) || !req.timezone().equals(v.timezone())
                || !req.priority().equals(v.priority()) || !Objects.equals(req.exclusiveGroup(), v.exclusiveGroup())) {
            throw new IllegalStateException("Invalid menu update receipt");
        }
        return result;
    }

    @Override
    public MenuConfigResultVO getMenu(String entity, String store, String menu) {
        MenuDefinitionPolicy.key(entity); MenuDefinitionPolicy.key(store); MenuDefinitionPolicy.key(menu);
        var result = call(() -> api.getMenu(new MenuInternalApi.GetQuery(entity, store, menu)));
        if (result == null || !MenuDefinitionPolicy.valid(result.config(), store)
                || !menu.equals(result.config().menuNo())) {
            throw new IllegalStateException("Invalid menu detail receipt");
        }
        return result;
    }

    @Override
    public PageData<MenuVO> listMenus(String entity, String store, ListMenusQuery query) {
        MenuDefinitionPolicy.key(entity); MenuDefinitionPolicy.key(store); MenuDefinitionPolicy.query(query);
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        var result = call(() -> api.listMenus(new MenuInternalApi.Lookup(entity, store, query)));
        var seen = new HashSet<String>();
        if (result == null || result.records() == null || result.page() == null || result.size() == null
                || result.total() == null || result.page() != page || result.size() != size
                || result.total() < 0 || result.total() < result.records().size() || result.records().size() > size
                || result.records().stream().anyMatch(v -> !MenuDefinitionPolicy.valid(v, store)
                        || !seen.add(v.menuNo()) || query != null && query.status() != null
                                && !query.status().equals(v.status()))) {
            throw new IllegalStateException("Invalid menu page receipt");
        }
        long offset = ((long) page - 1) * size;
        long expected = Math.min((long) size, Math.max(0L, result.total() - offset));
        if (result.records().size() != expected) throw new IllegalStateException("Invalid menu page cardinality");
        return PageData.of(result.records().stream().toList(), result.total(), page, size);
    }

    private static void context(String entity, String store, String actor, String key) {
        MenuDefinitionPolicy.key(entity); MenuDefinitionPolicy.key(store); MenuDefinitionPolicy.key(actor);
        if (key == null || key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private <T> T call(Supplier<T> work) {
        try { return ServiceCalls.call(ServiceName.FOOD, work); }
        catch (ServiceCallException failure) {
            var code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }
}
