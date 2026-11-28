package ai.neargo.shop.spi.org;

/**
 * 一项能力在哪生效（连锁整体设计 §4.1）。
 *
 * <p>{@link #GROUP} 就是业务上的「连锁」：系统一开始没有连锁概念，用「品牌」代替了，
 * 所以库表、接口、权限码里写的是 {@code BRAND} / {@code brand}，领域代码里一律叫 GROUP，
 * 两者只在 {@link ScopeCodes} 一处翻译。
 */
public enum ScopeType {
    /** 单家门店 */
    STORE,
    /** 经营主体（营业执照） */
    ENTITY,
    /** 连锁（存储编码 {@code BRAND}） */
    GROUP,
    /** 平台 */
    PLATFORM
}
