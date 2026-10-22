package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleMutationVO;
import ai.neargo.shop.food.dto.CreateBundleReq;
import ai.neargo.shop.food.dto.CreateBundleResultVO;
import ai.neargo.shop.food.dto.GetBundleVO;
import ai.neargo.shop.food.dto.ListBundlesQuery;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.service.BundleService;
import ai.neargo.shop.food.spi.BundleInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** One original FOOD call per attempt; malformed receipts cannot become successful management operations. */
public class RemoteBundleService implements BundleService {
    private final BundleInternalApi api;
    public RemoteBundleService(BundleInternalApi api) { this.api = api; }

    @Override
    public CreateBundleResultVO createBundle(String entity, String store, String actor,
            CreateBundleReq req, String key) {
        var result = invoke(() -> api.createBundle(
                new BundleInternalApi.CreateCommand(entity, store, actor, key, req)));
        if (result == null || req == null || !req.skuNo().equals(result.configNo()) || result.version() != 0
                || !"DRAFT".equals(result.status())) throw new IllegalStateException("Invalid bundle creation receipt");
        return result;
    }

    @Override
    public BundleMutationVO replaceDefinition(String entity, String store, String number, String actor,
            ReplaceBundleDefinitionReq req, String key) {
        var result = invoke(() -> api.replaceDefinition(
                new BundleInternalApi.UpdateCommand(entity, store, number, actor, key, req)));
        if (result == null || !number.equals(result.configNo()) || req == null || req.version() == null
                || req.version() < 0 || req.version() == Long.MAX_VALUE || result.version() != req.version() + 1) {
            throw new IllegalStateException("Invalid bundle mutation receipt");
        }
        return result;
    }

    @Override
    public GetBundleVO getBundle(String entity, String store, String number) {
        var result = invoke(() -> api.getBundle(new BundleInternalApi.GetQuery(entity, store, number)));
        if (result == null || !valid(result.config()) || !number.equals(result.config().configNo())) {
            throw new IllegalStateException("Invalid bundle detail receipt");
        }
        return result;
    }

    @Override
    public PageData<BundleConfigVO> listBundles(String entity, String store, ListBundlesQuery query) {
        var result = invoke(() -> api.listBundles(new BundleInternalApi.Lookup(entity, store, query)));
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        var seen = new HashSet<String>();
        if (result == null || result.records() == null || result.page() != page || result.size() != size
                || result.total() < result.records().size() || result.records().size() > size
                || result.records().stream().anyMatch(v -> !valid(v) || !seen.add(v.configNo()))) {
            throw new IllegalStateException("Invalid bundle page receipt");
        }
        return result;
    }

    private static boolean valid(BundleConfigVO v) {
        if (v == null || !identifier(v.configNo()) || v.version() < 0 || v.status() == null
                || !Set.of("DRAFT", "ACTIVE", "DISABLED").contains(v.status()) || v.data() == null
                || v.data().configVersion() < 0 || v.data().groups() == null || v.data().choices() == null
                || v.data().groups().size() > 30 || v.data().choices().size() > 100
                || v.data().publishedRevisionNo() != null && !identifier(v.data().publishedRevisionNo())) return false;
        var groups = new HashSet<String>(); var choices = new HashSet<String>();
        var members = new HashSet<List<String>>();
        for (var g : v.data().groups()) {
            if (g == null || !identifier(g.groupNo()) || !groups.add(g.groupNo()) || g.name() == null
                    || g.name().isBlank() || g.name().length() > 64 || g.sortNo() == null || g.sortNo() < 0
                    || !quantity(g.minQty(), 6, false) || !quantity(g.maxQty(), 6, false)
                    || g.minQty().compareTo(g.maxQty()) > 0
                    || g.baseSkuNo() != null && (!identifier(g.baseSkuNo()) || v.configNo().equals(g.baseSkuNo()))) {
                return false;
            }
        }
        for (var c : v.data().choices()) {
            if (c == null || !identifier(c.choiceNo()) || !choices.add(c.choiceNo()) || !groups.contains(c.groupNo())
                    || !identifier(c.skuNo()) || v.configNo().equals(c.skuNo())
                    || !members.add(List.of(c.groupNo(), c.skuNo())) || c.sortNo() == null || c.sortNo() < 0
                    || c.priceMode() == null || !Set.of("INCLUDED", "ADD_SKU", "DIFF_BASE").contains(c.priceMode())
                    || !quantity(c.unitsPerPick(), 3, true) || !quantity(c.minQty(), 6, false)
                    || !quantity(c.maxQty(), 6, true) || !quantity(c.defaultQty(), 6, false)
                    || c.minQty().compareTo(c.maxQty()) > 0 || c.defaultQty().compareTo(c.maxQty()) > 0) return false;
            if ("DIFF_BASE".equals(c.priceMode()) && v.data().groups().stream()
                    .noneMatch(g -> g.groupNo().equals(c.groupNo()) && g.baseSkuNo() != null)) return false;
        }
        return true;
    }

    private static boolean quantity(BigDecimal value, int scale, boolean positive) {
        return value != null && value.signum() >= (positive ? 1 : 0)
                && value.compareTo(new BigDecimal(scale == 3 ? "999999999.999" : "999999999999.999999")) <= 0
                && value.stripTrailingZeros().scale() <= scale;
    }
    private static boolean identifier(String value) {
        return value != null && !value.isBlank() && value.length() <= 32 && value.equals(value.trim());
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
