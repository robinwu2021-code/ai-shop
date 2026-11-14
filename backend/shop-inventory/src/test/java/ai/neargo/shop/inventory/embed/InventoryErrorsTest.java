package ai.neargo.shop.inventory.embed;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 进程边界上的错误码翻译：前端看到的商城码与搬迁前逐个相同。 */
class InventoryErrorsTest {

    @Test
    void everyInventoryErrorMapsToTheShopCodeUsedBeforeTheMove() {
        assertThat(InventoryErrors.toShop(InventoryError.STOCK_SHORT)).isEqualTo(ErrorCode.STOCK_SHORT_ITEMS);
        assertThat(InventoryErrors.toShop(InventoryError.COUNT_ALREADY_OPEN)).isEqualTo(ErrorCode.COUNT_ALREADY_OPEN);
        assertThat(InventoryErrors.toShop(InventoryError.INTERNAL)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        // 搬迁前就存在的八个码：一一对应，彼此不撞
        Set<ErrorCode> legacy = EnumSet.noneOf(ErrorCode.class);
        for (InventoryError e : EnumSet.range(InventoryError.BAD_REQUEST, InventoryError.COUNT_ALREADY_OPEN)) {
            legacy.add(InventoryErrors.toShop(e));
        }
        legacy.add(InventoryErrors.toShop(InventoryError.INTERNAL));
        assertThat(legacy).hasSize(8);
        for (InventoryError e : InventoryError.values()) {
            assertThat(InventoryErrors.toShop(e)).as(e.name()).isNotNull();
        }
    }

    @Test
    void advisorTranslatesExceptionsThrownByDomainServicesAndKeepsArgs() {
        Advisor advisor = InventoryEmbeddingConfiguration.errorTranslationAdvisor(
                InventoryErrorsTest.class.getName());
        ProxyFactory factory = new ProxyFactory(new FakeDomainService());
        factory.addAdvisor(advisor);
        Runnable proxy = (Runnable) factory.getProxy();
        assertThatThrownBy(proxy::run)
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.STOCK_SHORT_ITEMS);
                    assertThat(((BizException) e).args()).containsExactly("I-1,I-2");
                    assertThat(e.getCause()).isInstanceOf(InventoryException.class);
                });
    }

    /** 嵌套类名以测试类名为前缀，用来命中上面那个切点。 */
    static class FakeDomainService implements Runnable {
        @Override
        public void run() {
            throw InventoryException.of(InventoryError.STOCK_SHORT, "I-1,I-2");
        }
    }
}
