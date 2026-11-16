import ai.neargo.shop.notify.port.AliSmsGateway;
import ai.neargo.shop.notify.port.MitakeSmsGateway;
import ai.neargo.shop.notify.port.StubSmsGateway;
import ai.neargo.shop.spi.notify.SmsPort;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Run against the candidate jar's extracted libraries. Always sends to loopback only. */
public class RuntimeSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Private credential file path required");
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/SmSend", exchange -> {
            calls.incrementAndGet();
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!form.contains("dstaddr=0912345678") || !form.contains("username=") || !form.contains("password="))
                throw new AssertionError("Unexpected SMS protocol");
            byte[] body = "[1]\r\nmsgid=0551583405\r\nstatuscode=1\r\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource("smoke", Map.of(
                    "SHOP_SMS_STUB", "false", "SHOP_SMS_PROVIDER", "mitake",
                    "SHOP_SMS_MITAKE_CONFIG_FILE", args[0],
                    "SHOP_SMS_MITAKE_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort() + "/SmSend?CharsetURL=UTF-8")));
            context.register(AliSmsGateway.class, StubSmsGateway.class, MitakeSmsGateway.class);
            context.refresh();
            SmsPort gateway = context.getBean("smsGateway", SmsPort.class);
            if (!(gateway instanceof MitakeSmsGateway)) throw new AssertionError("Wrong provider");
            var receipt = gateway.sendOtp("+886912345678", "123456");
            if (!"0551583405".equals(receipt.providerMsgId()) || calls.get() != 1) throw new AssertionError("Invalid receipt");
            System.out.println("candidate-runtime-smoke: PASS; loopback-only; requests=1");
        } finally {
            server.stop(0);
        }
    }
}
