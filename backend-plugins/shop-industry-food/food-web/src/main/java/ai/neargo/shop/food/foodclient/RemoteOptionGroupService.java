package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.CreateOptionGroupResultVO;
import ai.neargo.shop.food.dto.ListOptionGroupsQuery;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OptionGroupResultVO;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.food.spi.OptionGroupInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Uses the original FOOD transport once per attempt and rejects malformed configuration receipts. */
public class RemoteOptionGroupService implements OptionGroupService {
    private final OptionGroupInternalApi api;
    public RemoteOptionGroupService(OptionGroupInternalApi api) { this.api = api; }

    @Override
    public CreateOptionGroupResultVO createOptionGroup(String entity, String store, String actor,
            CreateOptionGroupReq req, String key) {
        var result = invoke(() -> api.createOptionGroup(
                new OptionGroupInternalApi.CreateCommand(entity, store, actor, key, req)));
        if (result == null || !identifier(result.configNo()) || result.version() != 0
                || !"DRAFT".equals(result.status())) {
            throw new IllegalStateException("Invalid option-group creation receipt");
        }
        return result;
    }

    @Override
    public OptionGroupResultVO getOptionGroup(String entity, String store, String config) {
        var result = invoke(() -> api.getOptionGroup(new OptionGroupInternalApi.Identity(entity, store, config)));
        if (result == null || !valid(result.config()) || !config.equals(result.config().configNo())) {
            throw new IllegalStateException("Invalid option-group detail receipt");
        }
        return result;
    }

    @Override
    public PageData<OptionGroupConfigVO> listOptionGroups(String entity, String store, ListOptionGroupsQuery query) {
        var result = invoke(() -> api.listOptionGroups(new OptionGroupInternalApi.Lookup(entity, store, query)));
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        var seen = new HashSet<String>();
        if (result == null || result.records() == null || result.page() != page || result.size() != size
                || result.records().size() > size || result.total() < result.records().size()
                || result.records().stream().anyMatch(g -> !valid(g) || !seen.add(g.configNo()))) {
            throw new IllegalStateException("Invalid option-group page receipt");
        }
        return result;
    }

    private static boolean valid(OptionGroupConfigVO group) {
        if (group == null || !identifier(group.configNo()) || !"OPTION_GROUP".equals(group.kind())
                || group.revision() < 0 || group.status() == null
                || !Set.of("DRAFT", "ACTIVE", "DISABLED").contains(group.status()) || group.data() == null) {
            return false;
        }
        var data = group.data();
        return data.name() != null && !data.name().isBlank() && data.name().length() <= 64
                && data.minQty() != null && data.minQty().signum() >= 0 && data.maxQty() != null
                && data.maxQty().compareTo(data.minQty()) >= 0 && data.items() != null && data.selectionRules() != null;
    }

    private static boolean identifier(String value) {
        return value != null && !value.isBlank() && value.length() <= 32 && value.equals(value.trim());
    }

    private <T> T invoke(Supplier<T> call) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, call);
        } catch (ServiceCallException failure) {
            ErrorCode business = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (business != null) throw BizException.of(business);
            throw failure;
        }
    }
}
