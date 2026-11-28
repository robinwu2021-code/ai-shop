package ai.neargo.shop.spi.org;

import java.util.Objects;
import java.util.Optional;

/**
 * 门店所属连锁（连锁整体设计 §2.1、§4.1）。商户域实现，商品、库存、目录判「是不是连锁门店」只调它。
 *
 * <p>连锁标记 = 门店有一条生效中的品牌绑定，且品牌在用；品牌就是连锁。
 * 总部 = 品牌的签约主体（{@code mch_brand.legal_entity_no}），还没绑定时为空。
 */
public interface StoreChainPort {

    /** 没有生效中的连锁返回空 */
    Optional<Chain> chainOf(String tenantNo, String storeNo);

    /**
     * @param brandNo 连锁号（存储里的品牌号）
     * @param managementNo 所属商户集团号
     * @param headquartersEntityNo 总部主体；品牌还没绑签约主体时为空
     * @param storeEntityNo 这家门店的主体
     * @param revision 连锁门店范围版本，调用方冻结用
     */
    record Chain(String brandNo, String managementNo, String headquartersEntityNo, String storeEntityNo,
                 long revision) {

        /** 直营：门店主体就是总部主体 */
        public boolean direct() {
            return headquartersEntityNo != null && Objects.equals(headquartersEntityNo, storeEntityNo);
        }

        /** 加盟：有总部，且门店主体不是总部主体 */
        public boolean franchised() {
            return headquartersEntityNo != null && !Objects.equals(headquartersEntityNo, storeEntityNo);
        }

        public ScopeRef scope() {
            return new ScopeRef(ScopeType.GROUP, brandNo);
        }
    }
}
