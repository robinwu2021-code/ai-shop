package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.capability.ChannelCapability;
import ai.neargo.shop.pay.channel.fault.ChannelErrorMapper;
import ai.neargo.shop.pay.channel.handler.ChannelHandler;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 渠道处理器、能力实现、协议与发送口<b>无请求级可变状态</b>（任务表 B08 · 复核 D-15；TDD-支付服务-W2渠道层补完 S5 · AC7）。
 *
 * <p>同一个实例并发服务所有商户、所有请求（多商户后还要按收款商户切凭据）。一个可变字段 = 一次串单的机会：
 * 上一个请求的子商户号、凭据、单号留在字段里，下一个请求带着它发出去 —— 钱进了别人的账户，日志上一切正常。
 *
 * <p>扫描面：{@code ai.neargo.shop.pay.channel..} 生产类中实现了 {@link ChannelCapability} / {@link ChannelHandler} /
 * {@link ChannelErrorMapper} 的，以及 {@code channel.wechat..} / {@code channel.alipay..} / {@code channel.http..} /
 * {@code channel.platform..} 下的全部类。判据：实例字段一律 {@code final}，且类型不是可变集合实现（{@code HashMap}、
 * {@code ArrayList}…）或数组。接口类型的 final 字段视为构造时冻结（{@code Set.copyOf} 等）；计数器（{@code AtomicLong}）
 * 不承载请求，放行。进程内模拟银行（{@code TestPayGateway}）不是处理器，不在扫描面里。
 */
class GatewayStatelessTest {

    private static final String CHANNEL = "ai.neargo.shop.pay.channel";
    private static final List<String> SCANNED_PACKAGES = List.of(CHANNEL + ".wechat", CHANNEL + ".alipay",
            CHANNEL + ".http", CHANNEL + ".platform");
    private static final Set<String> MUTABLE = Set.of("java.util.HashMap", "java.util.LinkedHashMap",
            "java.util.TreeMap", "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet",
            "java.util.LinkedHashSet", "java.util.TreeSet", "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.CopyOnWriteArrayList", "java.lang.StringBuilder");

    @Test
    @DisplayName("★★★ AC7 处理器 / 能力实现 / 协议 / 发送口没有请求级可变状态")
    void handlersAreStateless() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(CHANNEL);

        List<JavaClass> scanned = classes.stream().filter(GatewayStatelessTest::inScope).toList();
        assertThat(scanned).as("扫描面里该有微信两个、支付宝、进程内渠道、协议与发送口 —— 少了就是判据失效")
                .extracting(JavaClass::getSimpleName)
                .contains("WechatDirectChannel", "WechatEcommerceChannel", "AlipayChannel", "InProcessChannel",
                        "WechatV3Protocol", "AlipayProtocol", "RecordingChannelClient", "CapabilityHandler");
        List<String> offenders = offenders(scanned);
        assertThat(offenders).as("这些字段会让并发请求互相串：%s\n  请求级的东西从 Invocation / ChannelAccountContext 取，不要放字段",
                offenders).isEmpty();
    }

    @Test
    @DisplayName("★ 判据自身能量出东西：非 final 字段、可变集合字段都会被认出来")
    void detectorFires() {
        JavaClasses sample = new ClassFileImporter().importClasses(Leaky.class, Cachey.class);
        assertThat(offenders(sample.stream().toList()))
                .anyMatch(s -> s.contains("Leaky.lastSubMerchant"))
                .anyMatch(s -> s.contains("Cachey.byOrder"));
    }

    private static boolean inScope(JavaClass c) {
        if (!c.getPackageName().startsWith(CHANNEL) || c.isInterface() || c.isRecord() || c.isEnum()) {
            return false;
        }
        return c.isAssignableTo(ChannelCapability.class) || c.isAssignableTo(ChannelHandler.class)
                || c.isAssignableTo(ChannelErrorMapper.class)
                || SCANNED_PACKAGES.stream().anyMatch(p -> c.getPackageName().equals(p) || c.getPackageName().startsWith(p + "."));
    }

    private static List<String> offenders(List<JavaClass> classes) {
        return classes.stream()
                .flatMap(c -> c.getFields().stream())
                .filter(f -> !f.getModifiers().contains(JavaModifier.STATIC))
                .filter(f -> !f.getName().startsWith("this$") && !f.getName().startsWith("$"))
                .filter(f -> !f.getModifiers().contains(JavaModifier.FINAL) || mutableType(f))
                .map(f -> f.getOwner().getSimpleName() + "." + f.getName() + " : " + f.getRawType().getName())
                .sorted()
                .toList();
    }

    private static boolean mutableType(JavaField f) {
        return f.getRawType().isArray() || MUTABLE.contains(f.getRawType().getName());
    }

    static final class Leaky {
        private String lastSubMerchant;
    }

    static final class Cachey {
        private final HashMap<String, String> byOrder = new HashMap<>();
    }
}
