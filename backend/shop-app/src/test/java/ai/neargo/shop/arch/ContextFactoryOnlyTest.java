package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.context.ChannelContextFactory;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChannelAccountContext} 只由 {@link ChannelContextFactory} 构造（TDD-支付服务-W2渠道层补完 S4 · AC5；
 * 代码结构与类清单 §5.4「构造 ctx 的唯一地方」）。
 *
 * <p>扫描面：{@code ai.neargo.shop} 下全部生产类（不含测试）。I2 时有两处各造各的 —— 桥按币种默认精度、补查写死 2 ——
 * 同一笔交易的查单与补查可以带着两种口径去问渠道，而不会有任何报错。
 */
class ContextFactoryOnlyTest {

    @Test
    @DisplayName("★★★ AC5 生产代码里只有 ChannelContextFactory 调 ChannelAccountContext 的构造器")
    void onlyFactoryConstructsContext() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ai.neargo.shop");

        List<String> offenders = offenders(classes);
        assertThat(classes.contain(ChannelContextFactory.class)).as("工厂没被扫到 —— 扫描面失效").isTrue();
        assertThat(offenders).as("这些类自己造了 ChannelAccountContext：%s\n  改走 ChannelContextFactory#from(ChannelRoute)",
                offenders).isEmpty();
    }

    @Test
    @DisplayName("★ 判据自身能量出东西：测试代码里的构造会被认出来")
    void detectorFires() {
        JavaClasses sample = new ClassFileImporter().importClasses(Sample.class, ChannelContextFactory.class);
        assertThat(offenders(sample)).anyMatch(s -> s.contains("Sample"));
    }

    private static List<String> offenders(JavaClasses classes) {
        return classes.stream()
                .filter(c -> !c.isEquivalentTo(ChannelContextFactory.class) && !c.isEquivalentTo(ChannelAccountContext.class))
                .flatMap(c -> c.getConstructorCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().isEquivalentTo(ChannelAccountContext.class))
                .map(JavaConstructorCall::getDescription)
                .distinct()
                .toList();
    }

    static final class Sample {
        ChannelAccountContext make() {
            return new ChannelAccountContext(null, "X", AccessMode.MERCHANT_OWN, null,
                    null, null, "CNY", 2);
        }
    }
}
