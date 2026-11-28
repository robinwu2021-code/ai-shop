package ai.neargo.shop.spi.org;

/** 一个范围：类型 + 编号（门店号 / 主体号 / 连锁号；平台为空）。 */
public record ScopeRef(ScopeType type, String no) {

    /** 从存储编码构造 */
    public static ScopeRef of(String code, String no) {
        return new ScopeRef(ScopeCodes.decode(code), no);
    }

    /** 落盘 / 对外的编码 */
    public String code() {
        return ScopeCodes.encode(type);
    }
}
