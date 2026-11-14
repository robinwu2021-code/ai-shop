package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.http.ChannelTransport;
import ai.neargo.shop.pay.channel.http.RecordingChannelClient;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.net.http.HttpClient;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 所有出站渠道 HTTP 必须经 {@link RecordingChannelClient}（复核 §3.2 W2 · D-15；TDD-支付服务-W2渠道层补完 S3 / S5 · AC4）。
 *
 * <p>扫描面：{@code ai.neargo.shop.pay.channel..} 的全部生产类。两条：
 * <ol>
 *   <li>只有 {@code channel.http} 可以用 HTTP 客户端（JDK {@code java.net.http}、{@code HttpURLConnection}、Spring
 *       {@code RestClient} / {@code RestTemplate} / {@code WebClient}、OkHttp、Apache HttpClient）；</li>
 *   <li>裸发送口 {@link ChannelTransport} 只有记录型客户端与装配类能碰。</li>
 * </ol>
 * 绕过记录型客户端发出去的请求，在调用记录与原文表里一个字都没有 —— 而那正是对账对不上时第一个要查的。
 * W2 之前 {@code WechatChannelClient} 自己握着 JDK HttpClient，这条在那时是红的。
 */
class RecordingClientOnlyTest {

    private static final String CHANNEL = "ai.neargo.shop.pay.channel";
    private static final List<String> HTTP_CLIENTS = List.of("java.net.http.", "java.net.HttpURLConnection",
            "java.net.URLConnection", "org.springframework.web.client.", "org.springframework.web.reactive.function.client.",
            "okhttp3.", "org.apache.hc.", "org.apache.http.");

    @Test
    @DisplayName("★★★ AC4 支付渠道层里只有 channel.http 用 HTTP 客户端；裸发送口只给记录型客户端")
    void onlyRecordingClientTalksHttp() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(CHANNEL);

        assertThat(classes.contain(RecordingChannelClient.class)).as("记录型客户端没被扫到 —— 扫描面失效").isTrue();
        assertThat(classes.size()).as("渠道层只扫到这么几个类 —— 扫描面失效").isGreaterThan(80);
        List<String> offenders = offenders(classes, c -> c.getPackageName().startsWith(CHANNEL));
        assertThat(offenders).as("这些类绕过了记录型客户端：%s\n  渠道 HTTP 一律经 RecordingChannelClient#send", offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 判据自身能量出东西：直接用 JDK HttpClient、直接拿裸发送口的类都会被认出来")
    void detectorFires() {
        JavaClasses sample = new ClassFileImporter().importClasses(RawHttp.class, RawTransport.class);
        assertThat(offenders(sample, c -> true))
                .anyMatch(s -> s.contains("RawHttp") && s.contains("java.net.http"))
                .anyMatch(s -> s.contains("RawTransport") && s.contains("ChannelTransport"));
    }

    private static List<String> offenders(JavaClasses classes, Predicate<JavaClass> inScope) {
        return classes.stream()
                .filter(inScope)
                .flatMap(c -> c.getDirectDependenciesFromSelf().stream())
                .filter(RecordingClientOnlyTest::violates)
                .map(Dependency::getDescription)
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean violates(Dependency d) {
        String origin = d.getOriginClass().getName();
        String target = d.getTargetClass().getName();
        boolean inHttpPackage = origin.startsWith(CHANNEL + ".http.");
        if (HTTP_CLIENTS.stream().anyMatch(target::startsWith)) {
            return !inHttpPackage;
        }
        if (target.equals(ChannelTransport.class.getName())) {
            return !inHttpPackage && !origin.startsWith(CHANNEL + ".config.");
        }
        return false;
    }

    static final class RawHttp {
        HttpClient client() {
            return HttpClient.newHttpClient();
        }
    }

    static final class RawTransport {
        ChannelTransport transport;
    }
}
