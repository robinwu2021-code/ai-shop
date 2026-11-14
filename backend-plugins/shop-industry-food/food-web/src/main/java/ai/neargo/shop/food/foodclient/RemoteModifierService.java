package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateModifierReq;
import ai.neargo.shop.food.dto.CreateModifierResultVO;
import ai.neargo.shop.food.dto.UpdateModifierReq;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.ModifierMutationVO;
import ai.neargo.shop.food.dto.ListModifiersQuery;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.food.spi.ModifierInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** A single original FOOD call per attempt; malformed receipts cannot become management success. */
public class RemoteModifierService implements ModifierService {
    private final ModifierInternalApi api;
    public RemoteModifierService(ModifierInternalApi api) { this.api = api; }

    @Override
    public CreateModifierResultVO createModifier(String entity, String store, String actor,
            CreateModifierReq req, String key) {
        var result = invoke(() -> api.createModifier(
                new ModifierInternalApi.CreateCommand(entity, store, actor, key, req)));
        if (result == null || !identifier(result.configNo()) || result.version() != 0
                || !"DRAFT".equals(result.status())) {
            throw new IllegalStateException("Invalid modifier creation receipt");
        }
        return result;
    }

    @Override
    public ModifierMutationVO updateModifier(String entity, String store, String number, String actor,
            UpdateModifierReq req, String key) {
        var result = invoke(() -> api.updateModifier(
                new ModifierInternalApi.UpdateCommand(entity, store, number, actor, key, req)));
        if (result == null || !number.equals(result.configNo()) || req == null || req.version() == null
                || req.version() < 0 || req.version() == Long.MAX_VALUE || result.version() != req.version() + 1) {
            throw new IllegalStateException("Invalid modifier update receipt");
        }
        return result;
    }

    @Override
    public PageData<ModifierConfigVO> listModifiers(String entity, String store, ListModifiersQuery query) {
        var result = invoke(() -> api.listModifiers(new ModifierInternalApi.Lookup(entity, store, query)));
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        var seen = new HashSet<String>();
        if (result == null || result.records() == null || result.page() != page || result.size() != size
                || result.total() < result.records().size() || result.records().size() > size
                || result.records().stream().anyMatch(v -> !valid(v) || !seen.add(v.configNo()))) {
            throw new IllegalStateException("Invalid modifier page receipt");
        }
        return result;
    }

    private static boolean valid(ModifierConfigVO v) {
        if (v == null || !identifier(v.configNo()) || !Set.of("ADDON", "COMBO").contains(v.kind())
                || v.version() < 0 || v.status() == null
                || !Set.of("DRAFT", "ACTIVE", "DISABLED").contains(v.status())
                || v.data() == null || v.data().name() == null || v.data().name().isBlank()
                || v.data().name().length() > 64 || v.data().components().size() > 100
                || v.data().salesSkuNo() != null && !identifier(v.data().salesSkuNo())
                || v.data().publishedRevisionNo() != null && !identifier(v.data().publishedRevisionNo())) return false;
        if ("ADDON".equals(v.kind()) != v.data().components().isEmpty()) return false;
        try {
            ai.neargo.shop.food.policy.ModifierInventoryMode.resolve(
                    v.kind(), v.data().salesSkuNo(), v.data().inventoryMode());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        var members = new HashSet<String>();
        var ids = new HashSet<String>();
        for (var c : v.data().components()) {
            if (c == null || !identifier(c.componentNo()) || !identifier(c.memberNo())
                    || !ids.add(c.componentNo()) || !members.add(c.memberNo()) || v.configNo().equals(c.memberNo())
                    || c.quantity() == null || c.quantity().signum() <= 0
                    || c.quantity().stripTrailingZeros().scale() > 3
                    || c.quantity().compareTo(new BigDecimal("999999999.999")) > 0) return false;
        }
        return true;
    }

    private static boolean identifier(String v) {
        return v != null && !v.isBlank() && v.length() <= 32 && v.equals(v.trim());
    }

    private <T> T invoke(Supplier<T> call) {
        try { return ServiceCalls.call(ServiceName.FOOD, call); }
        catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
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
