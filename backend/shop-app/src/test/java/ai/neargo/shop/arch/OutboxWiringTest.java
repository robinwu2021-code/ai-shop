package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 必须有**生产调用方**。
 *
 * <p><b>这条守卫的来历</b>：{@code OutboxDispatcher.dispatchPending()} 写好之后，
 * 它的类注释写着「由定时任务调用」—— 而那个定时任务从来没被写出来。
 * 全仓生产代码对它的引用只有构造器，**只有测试在调**。
 *
 * <p>后果是真实部署里 {@code sys_outbox} 永远停在 PENDING，
 * 订单状态变化的站内信一条都发不出去。而**整套测试是绿的** ——
 * 因为测试自己手动调了 {@code dispatchPending()}：
 * <b>测试替调度器站了岗，于是缺口在测试里不可见。</b>
 *
 * <p>所以这条守卫扫的是**生产源码**，不看测试：测试里有调用恰恰是问题的伪装。
 */
@DisplayName("Outbox 装配")
class OutboxWiringTest {

    private static final Path BACKEND = Paths.get("").toAbsolutePath().getParent();

    /** 生产源码里引用了 {@code dispatchPending} 的文件（排除它自己的定义） */
    private static List<Path> productionCallers() throws IOException {
        List<Path> hits = new ArrayList<>();
        for (Path module : Files.list(BACKEND).filter(Files::isDirectory).toList()) {
            Path src = module.resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (var files = Files.walk(src)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (f.getFileName().toString().equals("OutboxDispatcher.java")) {
                        continue;
                    }
                    if (Files.readString(f, StandardCharsets.UTF_8).contains("dispatchPending")) {
                        hits.add(f);
                    }
                }
            }
        }
        return hits;
    }

    @Test
    @DisplayName("★★★ dispatchPending 必须有生产调用方 —— 只有测试在调时，站内信一条都发不出去而测试全绿")
    void dispatcherHasAProductionCaller() throws IOException {
        assertThat(productionCallers())
                .as("没有任何生产代码调用 OutboxDispatcher.dispatchPending —— "
                        + "事件会永远停在 PENDING，OrderEventConsumer 一次都不触发。\n"
                        + "  这不会让任何测试变红（测试自己手动调它），也不会有任何报错，\n"
                        + "  症状只有一个：用户收不到站内信，而没人知道为什么。")
                .isNotEmpty();
    }

    @Test
    @DisplayName("★★★ 那个调用方必须是定时任务 —— 挂在别处等于只在某个请求里偶然投递")
    void theCallerIsAScheduledJob() throws IOException {
        List<String> notScheduled = new ArrayList<>();
        for (Path f : productionCallers()) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            if (!text.contains("@Scheduled")) {
                notScheduled.add(f.getFileName().toString());
            }
        }
        assertThat(notScheduled)
                .as("这些地方调了 dispatchPending 但不是定时任务：%s\n"
                        + "  挂在请求链路上的话，投递就取决于「有没有人恰好打开某个页面」——\n"
                        + "  夜里没人访问时，事件一整晚都躺着。", notScheduled)
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 定时任务必须加分布式锁 —— 多实例下两个 worker 会同时扫同一批")
    void theJobIsLocked() throws IOException {
        List<String> unlocked = new ArrayList<>();
        for (Path f : productionCallers()) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            if (text.contains("@Scheduled") && !text.contains("@SchedulerLock")) {
                unlocked.add(f.getFileName().toString());
            }
        }
        assertThat(unlocked)
                .as("这些投递任务没加 @SchedulerLock：%s\n"
                        + "  投递本身是幂等的（消费者靠 dedup_key），所以重复投递不会发重复消息 ——\n"
                        + "  锁防的是两个实例同时扫同一批造成的白工与行锁竞争。", unlocked)
                .isEmpty();
    }
}
