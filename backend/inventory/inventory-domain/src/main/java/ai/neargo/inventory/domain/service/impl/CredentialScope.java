package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.Capability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 开放凭证写进密钥库 {@code sec_key_version.scope} 的内容（TDD-基础能力库对齐「1h 开放应用」Q18）。
 *
 * <p>格式：分号分隔的 {@code 键=值}，键按下列顺序、没有的省略 ——
 * <ul>
 *   <li>{@code scopes=}逗号分隔的权限，必有：开放接口凭证如 {@code read,stock:sync}，服务凭证固定 {@code service}；</li>
 *   <li>{@code caps=}逗号分隔的能力包（{@link Capability} 名，排序）：服务凭证可有，空 = 只按业主档案；</li>
 *   <li>{@code owner=}{@code inv_owner.owner_id}：业主凭证；</li>
 *   <li>{@code system=}{@code system_code}：系统凭证。</li>
 * </ul>
 * 例：{@code scopes=read,stock:sync;owner=OWN2026…}、{@code scopes=service;caps=DISPATCH_POOL,SERIAL;owner=OWN…}、
 * {@code scopes=service;system=SHOP}。
 *
 * <p><b>owner / system 是绑定，不是冗余</b>：凭证在密钥库里归 {@code inv_owner.id} / {@code inv_client_system.id}（数字主键），
 * 而库存各实例（电商、设备）各有一个库、主键各自从 1 起，却共用同一个密钥库。只看数字主键，设备套 7 号业主的凭证
 * 到电商套就成了电商 7 号业主的 —— 所以认凭证时还要核对本库那一行的业务键与这里记的一致。
 */
record CredentialScope(String scopes, Set<Capability> capabilities, String owner, String system) {

    CredentialScope {
        // 分隔符进了值就能伪造出第二个 owner= / system=；scopes 是运营手填的，在这里拦
        if (scopes == null || scopes.isBlank() || scopes.contains(";") || scopes.contains("=")) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "scopes");
        }
        capabilities = capabilities == null || capabilities.isEmpty() ? Set.of() : Set.copyOf(capabilities);
    }

    String encode() {
        List<String> parts = new ArrayList<>();
        parts.add("scopes=" + scopes);
        if (!capabilities.isEmpty()) {
            parts.add("caps=" + capabilities.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        }
        if (owner != null) {
            parts.add("owner=" + owner);
        }
        if (system != null) {
            parts.add("system=" + system);
        }
        return String.join(";", parts);
    }

    static CredentialScope parse(String scope) {
        Map<String, String> values = new HashMap<>();
        for (String part : scope.split(";")) {
            int eq = part.indexOf('=');
            values.put(part.substring(0, eq), part.substring(eq + 1));
        }
        String caps = values.get("caps");
        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        if (caps != null) {
            Arrays.stream(caps.split(",")).map(Capability::valueOf).forEach(capabilities::add);
        }
        return new CredentialScope(values.get("scopes"), capabilities, values.get("owner"), values.get("system"));
    }

    /** 要求的 scope 在不在里面；不要求（空）一律放行 */
    boolean allows(String required) {
        if (required == null || required.isBlank()) {
            return true;
        }
        return Arrays.stream(scopes.split(",")).map(String::trim).anyMatch(required::equals);
    }
}
