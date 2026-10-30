package ai.neargo.shop.commerce.channel.mock;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import tools.jackson.databind.json.JsonMapper;

/** Standalone external test peer; reuses the existing protocol and never starts business owners. */
public final class CommerceMockApplication {
    private CommerceMockApplication() {}

    public static void main(String[] args) throws Exception {
        var platform = new MockCommercePlatform(JsonMapper.builder().findAndAddModules().build(),
                Path.of(System.getenv("COMMERCE_MOCK_DIRECTORY")), System.getenv("COMMERCE_MOCK_TOKEN"),
                Integer.parseInt(System.getenv().getOrDefault("COMMERCE_MOCK_PORT", "9410")));
        var stopped = new CountDownLatch(1);
        platform.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            platform.close();
            stopped.countDown();
        }, "commerce-mock-shutdown"));
        System.out.println("Mock commerce listening on " + platform.port());
        stopped.await();
    }
}
