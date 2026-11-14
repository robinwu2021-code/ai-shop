package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 边缘网关规则（支付服务-安全规范 §4，G1 / G2）：守的是 {@code deploy/tencent/nginx/} 下的线上快照。
 * nginx 配置不经任何编译器，写错一个字符的后果是「运营后台对全网开放」或「限流按伪造的 IP 算」—— 都不报错。
 */
class NginxEdgeRulesTest {

    private static final Path DIR = Path.of("..", "..", "deploy", "tencent", "nginx").toAbsolutePath().normalize();
    private static final String OPS_ALLOW = "include /etc/nginx/ai-shop/ops-allow.conf;";

    private static List<Path> confs() throws IOException {
        try (Stream<Path> s = Files.list(DIR)) {
            return s.filter(p -> p.toString().endsWith(".conf")).sorted().toList();
        }
    }

    /** 逐个 location 块：头 + 体（按花括号配对） */
    private static List<String[]> locations(String conf) {
        List<String[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\s*location\\s+([^{]+)\\{").matcher(conf);
        while (m.find()) {
            int depth = 1;
            int i = m.end();
            while (i < conf.length() && depth > 0) {
                char c = conf.charAt(i++);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
            out.add(new String[] {m.group(1).trim(), conf.substring(m.end(), i - 1)});
        }
        return out;
    }

    private static String withoutComments(String conf) {
        return conf.replaceAll("(?m)#.*$", "");
    }

    @Test
    @DisplayName("★★★ G2：转发头一律覆写成 $remote_addr，不追加（追加 = 客户端写的最左值原样传下去）")
    void forwardedForIsOverwrittenNotAppended() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path p : confs()) {
            if (withoutComments(Files.readString(p)).contains("$proxy_add_x_forwarded_for")) {
                bad.add(p.getFileName().toString());
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    @DisplayName("★★★ G1：运营入口（/ops、/ops-web、/pay-ops）只对白名单网段，其余 deny all；公共正则 location 里不得有 ops")
    void opsOnlyFromAllowList() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path p : confs()) {
            String conf = withoutComments(Files.readString(p));
            for (String[] loc : locations(conf)) {
                String head = loc[0];
                String body = loc[1];
                boolean regex = head.startsWith("~");
                if (regex && head.matches(".*[(|]ops[|)].*")) {
                    bad.add(p.getFileName() + " 正则 location 含 ops：" + head);
                }
                String path = head.replaceFirst("^(\\^~|=)\\s*", "");
                boolean ops = !regex && (path.startsWith("/ops") || path.startsWith("/pay-ops"));
                boolean redirectOnly = body.trim().startsWith("return 30");
                if (ops && !redirectOnly && !(body.contains(OPS_ALLOW) && body.contains("deny all;"))) {
                    bad.add(p.getFileName() + " " + head + " 缺白名单 include 或 deny all");
                }
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    @DisplayName("★★ 反代到应用的 server 都把 /internal/ 限死在本机")
    void internalDeniedOnEveryAppServer() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path p : confs()) {
            String conf = withoutComments(Files.readString(p));
            for (String server : conf.split("(?m)^\\s*server\\s*\\{")) {
                if (!server.contains("proxy_pass http://127.0.0.1:8081")) {
                    continue;
                }
                boolean ok = locations(server).stream().anyMatch(l -> l[0].equals("^~ /internal/")
                        && l[1].contains("deny all;"));
                if (!ok) {
                    bad.add(p.getFileName().toString());
                }
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    @DisplayName("★★ 支付独立切换件（M7.5）：四个直连入口转 8083、转发头覆写、不碰 /internal 与运营 / 商家 / 小程序；平时没有任何 server 引用它")
    void payStandaloneCutoverSnippet() throws IOException {
        Path snippet = DIR.resolve("pay-standalone.locations.example");
        String body = withoutComments(Files.readString(snippet));
        List<String[]> locs = locations(body);
        assertThat(locs.stream().map(l -> l[0].trim()).toList())
                .containsExactly("^~ /open/pay/", "^~ /terminal/pay/", "^~ /pay/callback/", "^~ /checkout/v1/");
        assertThat(locs).allSatisfy(l -> assertThat(l[1]).contains("proxy_pass http://127.0.0.1:8083;")
                .contains("X-Forwarded-For   $remote_addr").doesNotContain("$proxy_add_x_forwarded_for"));
        assertThat(body).doesNotContain("/internal").doesNotContain("/ops").doesNotContain("/biz").doesNotContain("/mp/");
        for (Path p : confs()) {
            assertThat(Files.readString(p)).as("%s 已经 include 了切换件 —— 切换要按 M7.7 演练后手工做", p.getFileName())
                    .doesNotContain("pay-standalone.locations");
        }
    }
}
