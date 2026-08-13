package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每一个定时任务都要满足的四条约定。
 *
 * <p>{@link OutboxWiringTest} 管的是 outbox 那**一条**任务有没有被调起来；
 * 这里管的是**所有**任务的形状。分成两个文件是因为两者的失败含义不同：
 * 那边红了是「一条链路断了」，这边红了是「新加的任务少了一样东西」。
 *
 * <p><b>为什么扫源码而不是扫 Bean</b>：这四条里有三条（cron 可配、加锁、只在 worker）
 * 在测试上下文里根本观察不到 —— 测试不是 worker profile，
 * 这些任务的 Bean 压根不存在，扫 Bean 只会得到一个空集合然后全绿。
 * <b>「什么都没扫到」是这类守卫最常见的死法</b>，所以下面每条都先断言样本非空。
 */
@DisplayName("定时任务约定")
class ScheduledJobConventionTest {

    private static final Path BACKEND = Paths.get("").toAbsolutePath().getParent();

    private static final Pattern SCHEDULED =
            Pattern.compile("@Scheduled\\s*\\(\\s*cron\\s*=\\s*\"([^\"]*)\"");

    /**
     * 去掉注释行。
     *
     * <p><b>这一步是红检逼出来的</b>：第一版直接对全文做 {@code contains("@SchedulerLock")}，
     * 于是把注解<b>注释掉</b>时守卫依然是绿的 —— 而「先注释掉试试」正是注解最常见的失效方式，
     * 恰恰是这条守卫最该拦住的那一种。
     *
     * <p>只剥「整行以 {@code //}、{@code /*}、{@code *} 开头」的行，不做完整词法分析：
     * 行中间的 {@code //} 可能在字符串字面量里（URL），剥了会毁掉源码语义。
     * 守卫宁可少剥一点，也不能把没注释的东西当成注释。
     */
    private static String stripComments(String java) {
        StringBuilder out = new StringBuilder(java.length());
        for (String line : java.split("\n", -1)) {
            String t = line.strip();
            if (!t.startsWith("//") && !t.startsWith("/*") && !t.startsWith("*")) {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** 文件名 → **已剥注释**的源码。只含真的挂了 {@code @Scheduled(cron=...)} 的生产类 */
    private static Map<String, String> jobSources() throws IOException {
        Map<String, String> found = new LinkedHashMap<>();
        for (Path module : Files.list(BACKEND).filter(Files::isDirectory).toList()) {
            Path src = module.resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (var files = Files.walk(src)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = stripComments(Files.readString(f, StandardCharsets.UTF_8));
                    if (SCHEDULED.matcher(code).find()) {
                        found.put(f.getFileName().toString(), code);
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("★★ 扫得到任务 —— 这条守卫本身不能因为「一个都没找到」而空转全绿")
    void thereAreJobsToCheck() throws IOException {
        assertThat(jobSources())
                .as("一个 @Scheduled(cron=…) 都没扫到。要么正则跟不上写法变化，\n"
                        + "  要么工作目录不是 backend/<module> —— 无论哪种，"
                        + "下面三条都在**假装通过**")
                .hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("★★★ cron 必须来自配置 —— 硬编码时，调频率要改代码发版，而需要调的时刻正是出事的时刻")
    void cronComesFromConfig() throws IOException {
        List<String> hardcoded = new ArrayList<>();
        for (var e : jobSources().entrySet()) {
            Matcher m = SCHEDULED.matcher(e.getValue());
            while (m.find()) {
                if (!m.group(1).startsWith("${")) {
                    hardcoded.add(e.getKey() + " → " + m.group(1));
                }
            }
        }
        assertThat(hardcoded)
                .as("这些 cron 写死在代码里：%s\n"
                        + "  改成 \"${shop.job.<名字>.cron:<原值>}\"，并在 application.yml 的\n"
                        + "  shop.job 下登记一条。默认值保持原值，行为逐字节不变。", hardcoded)
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ 必须加 @SchedulerLock —— worker 扩到两个实例时，每一轮都会被跑两遍")
    void everyJobIsLocked() throws IOException {
        List<String> unlocked = new ArrayList<>();
        jobSources().forEach((name, text) -> {
            if (!text.contains("@SchedulerLock")) {
                unlocked.add(name);
            }
        });
        assertThat(unlocked)
                .as("这些定时任务没加 @SchedulerLock：%s\n"
                        + "  扩容是运维在容量吃紧时做的动作，不会回来问代码准备好没有 ——\n"
                        + "  锁要在第一个实例上线时就在，而不是等扩容前补。", unlocked)
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 必须 @Profile(\"worker\") —— 现在靠 SchedulingConfig 兜着，但那是一次编辑之遥")
    void everyJobIsWorkerOnly() throws IOException {
        List<String> everywhere = new ArrayList<>();
        jobSources().forEach((name, text) -> {
            if (!text.contains("@Profile(\"worker\")")) {
                everywhere.add(name);
            }
        });
        assertThat(everywhere)
                .as("""
                        这些定时任务的类上没有 @Profile("worker")：%s

                          今天它们确实不会在 api/ops 上跑 —— 因为 SchedulingConfig 自己是
                          @Profile("worker")，没有它 @EnableScheduling 就不存在，@Scheduled 连解析都不会发生。

                          **所以这条守的不是当下的 bug，是那一次编辑**：哪天有人为了某个
                          非 worker 的任务把 @EnableScheduling 挪出去，这些任务会**静默地**
                          在每个 api 实例上跑起来。那时的症状是批量任务和下单抢连接池，
                          在监控上看起来像「数据库变慢了」，没有任何东西指向这次改动。

                          两道闸都在，才是「挡住了」；只有一道，是「碰巧没漏」。""", everywhere)
                .isEmpty();
    }
}
