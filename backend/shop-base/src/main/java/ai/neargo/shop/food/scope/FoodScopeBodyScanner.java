package ai.neargo.shop.food.scope;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 从内部请求体里取出范围字段，供签发方核对上下文、验签方核对声明。
 *
 * <p>内部合同的字段名不统一（{@code entityNo/storeNo/actorNo} 与 {@code entity/store/actor} 并存， C 端买家在 {@code
 * CartAccess.buyerNo}，且常嵌套），所以按键名递归遍历整棵 JSON， 取所有非空字符串值；任何一个值不符即不符 —— 一个请求体里出现两个门店也逃不过。
 */
public final class FoodScopeBodyScanner {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Set<String> ENTITY = Set.of("entityNo", "entity");
    private static final Set<String> STORE = Set.of("storeNo", "store");
    private static final Set<String> ACTOR = Set.of("actorNo", "actor");
    private static final Set<String> BUYER = Set.of("buyerNo");

    private FoodScopeBodyScanner() {}

    public record Scope(
            Set<String> entities, Set<String> stores, Set<String> actors, Set<String> buyers) {}

    public static Scope scan(byte[] json) {
        var scope = new Scope(new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        if (json == null || json.length == 0) return scope;
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException e) {
            return scope;
        }
        walk(root, scope);
        return scope;
    }

    private static void walk(JsonNode node, Scope scope) {
        if (node == null) return;
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                JsonNode value = field.getValue();
                if (value != null && value.isString() && !value.asString().isBlank()) {
                    String key = field.getKey();
                    if (ENTITY.contains(key)) scope.entities().add(value.asString());
                    else if (STORE.contains(key)) scope.stores().add(value.asString());
                    else if (ACTOR.contains(key)) scope.actors().add(value.asString());
                    else if (BUYER.contains(key)) scope.buyers().add(value.asString());
                } else {
                    walk(value, scope);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) walk(item, scope);
        }
    }
}
