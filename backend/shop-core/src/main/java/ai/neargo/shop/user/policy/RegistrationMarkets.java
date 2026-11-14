package ai.neargo.shop.user.policy;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 注册时可选的经营地区（TDD-注册地区与三步建店 AC3）。
 *
 * <p>端上自报的地区<b>只在清单内才算数</b>，其余（空、拼错、本部署没开放的）一律落部署默认 ——
 * 不报错：注册是第一步，因为一个地区参数把人挡在门外，比让他落在默认地区更糟；
 * 而清单本身由部署配置决定，正式包不显示选择，参数只会是空或该包写死的地区。
 *
 * @param defaultMarket 部署默认（{@code shop.merchant.default-market}）
 * @param allowed       可注册地区（{@code shop.merchant.registration-markets}），恒含部署默认
 */
public record RegistrationMarkets(String defaultMarket, Set<String> allowed) {

    /** 与 {@code shop.merchant.default-market} 的缺省值同一个 */
    private static final String FALLBACK = "CN";

    /**
     * @param csv           逗号分隔；空 = 只有部署默认
     * @param defaultMarket 部署默认；空按 CN（与配置缺省一致）
     */
    public static RegistrationMarkets of(String csv, String defaultMarket) {
        String fallback = normalize(defaultMarket);
        String def = fallback == null ? FALLBACK : fallback;
        Set<String> allowed = csv == null ? Set.of(def)
                : Stream.concat(Arrays.stream(csv.split(",")).map(RegistrationMarkets::normalize), Stream.of(def))
                        .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
        return new RegistrationMarkets(def, allowed);
    }

    /** 自报地区在清单内就用它，否则部署默认 */
    public String resolve(String requested) {
        String code = normalize(requested);
        return code != null && allowed.contains(code) ? code : defaultMarket;
    }

    private static String normalize(String code) {
        if (code == null || code.isBlank()) return null;
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
