package ai.neargo.shop.spi.org;

/**
 * 范围类型的存储 / 接口编码 ↔ 领域类型，唯一的翻译点。
 *
 * <p>{@code BRAND} 是 {@link ScopeType#GROUP}（连锁）的历史编码：储值发行版本、余额批次、结算单都冻结了它，
 * 所以落盘与对外一律仍写 {@code BRAND}，读进来翻成 GROUP。{@code "BRAND"} 字面量只许出现在这里、
 * 迁移脚本与测试夹具（守卫 {@code BrandCodeLiteralTest}）。
 */
public final class ScopeCodes {

    /** 连锁的存储编码 */
    public static final String BRAND = "BRAND";

    private ScopeCodes() {
    }

    public static ScopeType decode(String code) {
        if (BRAND.equals(code)) {
            return ScopeType.GROUP;
        }
        if (code == null || ScopeType.GROUP.name().equals(code)) {
            throw new IllegalArgumentException("unknown scope code: " + code);
        }
        return ScopeType.valueOf(code);
    }

    public static String encode(ScopeType type) {
        return type == ScopeType.GROUP ? BRAND : type.name();
    }

    /** 这个编码是不是连锁 */
    public static boolean isGroup(String code) {
        return BRAND.equals(code);
    }
}
