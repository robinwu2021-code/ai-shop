package ai.neargo.inventory.domain.support;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;

import java.util.HashSet;
import java.util.List;

/**
 * 物料身份的边界规则：键长、名称长度、计量单位、数据来源。
 *
 * <p>与 ai-shop 物料目录契约的校验逐条相同 —— 本地提供方与远程调用方用同一组边界，
 * 才挡得住无界的身份查询。{@code shop-inventory} 的 {@code DomainRuleParityTest} 比对两边。
 */
public final class MaterialRules {

    private MaterialRules() {
    }

    public static boolean validKey(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.equals(value.strip());
    }

    public static boolean validDefinition(String item, String entity, String name, String unit,
                                          Integer weighed, String source) {
        return validKey(item, 32) && validKey(entity, 32) && name != null && !name.isBlank()
                && name.length() <= 128 && validKey(unit, 16) && weighed != null && (weighed == 0 || weighed == 1)
                && ("OWN".equals(source) || "SYNCED".equals(source));
    }

    /** 一次最多查 256 个、不许重复、每个键合法；否则 {@link InventoryError#BAD_REQUEST}。 */
    public static void validateLookup(String entityNo, List<String> itemIds) {
        if (!validKey(entityNo, 32)
                || itemIds == null
                || itemIds.isEmpty()
                || itemIds.size() > 256
                || new HashSet<>(itemIds).size() != itemIds.size()
                || itemIds.stream().anyMatch(id -> !validKey(id, 32))) {
            throw InventoryException.of(InventoryError.BAD_REQUEST);
        }
    }
}
