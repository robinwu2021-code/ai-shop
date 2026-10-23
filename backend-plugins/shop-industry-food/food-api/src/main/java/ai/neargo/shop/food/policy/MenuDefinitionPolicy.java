package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CreateMenuReq;
import ai.neargo.shop.food.dto.ListMenusQuery;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.UpdateMenuReq;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import java.time.ZoneId;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Shared validation keeps embedded and remote headers on the same contract, without defining operating hours. */
public final class MenuDefinitionPolicy {
    private MenuDefinitionPolicy() { }
    public static void key(String value) {
        require(MaterialCatalogFacts.validKey(value, 32));
    }
    public static void create(CreateMenuReq req) {
        require(req != null);
        fields(req.name(), req.menuType(), req.timezone(), req.priority(), req.exclusiveGroup());
    }
    public static void update(UpdateMenuReq req) {
        require(req != null && req.version() != null && req.version() >= 0 && req.version() < Long.MAX_VALUE);
        fields(req.name(), req.menuType(), req.timezone(), req.priority(), req.exclusiveGroup());
    }
    public static void query(ListMenusQuery q) {
        if (q == null) return;
        require((q.page() == null || q.page() >= 1) && (q.size() == null || q.size() >= 1 && q.size() <= 100));
        require(q.status() == null || statuses().contains(q.status()));
        require(q.keyword() == null || text(q.keyword(), 64));
    }
    public static boolean valid(MenuVO v, String store) {
        if (v == null || !MaterialCatalogFacts.validKey(v.menuNo(), 32) || !store.equals(v.storeNo())
                || v.version() == null || v.version() < 0 || v.configVersion() == null || v.configVersion() < 0
                || !statuses().contains(v.status() == null ? "" : v.status())) return false;
        try { fields(v.name(), v.menuType(), v.timezone(), v.priority(), v.exclusiveGroup()); return true; }
        catch (BizException invalid) { return false; }
    }
    private static List<String> statuses() { return List.of("DRAFT", "PUBLISHED", "DISABLED"); }
    private static void fields(String name, String type, String timezone, Integer priority, String group) {
        require(text(name, 64) && type != null && List.of("STANDARD", "TIME", "SEASON", "EVENT").contains(type)
                && timezone != null && timezone.length() <= 64 && ZoneId.getAvailableZoneIds().contains(timezone)
                && priority != null && (group == null || text(group, 32)));
    }
    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static void require(boolean condition) {
        if (!condition) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    /** Provided booleans must not accept string or numeric coercion. */
    public static final class BooleanDeserializer extends ValueDeserializer<Boolean> {
        @Override public Boolean deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() == JsonToken.VALUE_TRUE) return true;
            if (parser.currentToken() == JsonToken.VALUE_FALSE) return false;
            return context.reportInputMismatch(Boolean.class, "Menu boolean requires a boolean token");
        }
    }

    /** All int32 JSON fields must be actual integer tokens; optional nulls are validated by their caller. */
    public static final class IntegerDeserializer extends ValueDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "Menu integer requires an integer token");
            }
            return parser.getIntValue();
        }
    }
}
