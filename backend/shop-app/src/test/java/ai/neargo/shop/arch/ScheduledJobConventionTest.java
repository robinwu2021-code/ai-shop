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
 * 每一个定时任务都要满足的几条约定。
 *
 * <p>{@link OutboxWiringTest} 管的是 outbox 那**一条**任务有没有被调起来；
 * 这里管的是**所有**任务的形状。分成两个文件是因为两者的失败含义不同：
 * 那边红了是「一条链路断了」，这边红了是「新加的任务少了一样东西」。
 *
 * <p><b>为什么扫源码而不是扫 Bean</b>：其中几条（cron 可配、加锁、排期的门）
 * 在测试上下文里根本观察不到 —— 默认上下文既不是 worker profile、也没打开 shop.job，
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

    /**
     * 每个 {@code @SchedulerLock(name = "x")} 都要有一个 {@code JobDeclaration("x", …)}。
     *
     * <p><b>2026-09-02 这条守卫是被一次真实的静默停摆逼出来的。</b>进销存那三个任务
     * （事件投递 / 预留回收 / 日快照）写好了、注解挂着、开关也开着，
     * <b>在生产上一次都没跑过</b>：
     *
     * <pre>
     * inv_outbox   212 行全部 PENDING，SENT 0 行
     * retry_count  max = 0        ← 连投都没投过，不是投失败
     * </pre>
     *
     * 根因是两条触发路径同时不通，而各自看都合理：
     * <ul>
     *   <li>{@code @EnableScheduling} 挂 {@code @Profile("worker")}，生产是 {@code api,ops} ——
     *       <b>{@code @Scheduled} 连解析都不会发生</b></li>
     *   <li>另一条是独立调度器按 {@code job_definition} 打进来，进那张表要靠
     *       {@code JobDeclaration} bean —— 那三个任务一个都没声明</li>
     * </ul>
     *
     * <p><b>它没有任何症状</b>：类在、注解在、开关开着，只是没有任何东西去读那个注解。
     * 而这正是守卫该管的形状 —— 人眼看代码看不出来，要把两处对起来才看得出来。
     *
     * <p>判据取 {@code @SchedulerLock} 的 name 而不是方法名：锁名就是任务的身份，
     * {@code JobDeclaration} 的 handlerName 与它一一对应（现有任务都是这么写的）。
     */
    @Test
    @DisplayName("★★★ 每个定时任务都要有 JobDeclaration —— 没有的那个在生产上根本不会被触发")
    void everyScheduledJobIsDeclared() throws IOException {
        Pattern lockName = Pattern.compile("@SchedulerLock\\s*\\(\\s*name\\s*=\\s*\"([^\"]*)\"");
        List<String> locks = new ArrayList<>();
        for (var e : jobSources().entrySet()) {
            Matcher m = lockName.matcher(e.getValue());
            while (m.find()) {
                locks.add(m.group(1));
            }
        }
        // 扫描面自己的断言：一个锁名都抽不到 = 正则失效，下面那句会空跑成绿
        assertThat(locks).as("一个 @SchedulerLock(name=…) 都没抽到，先怀疑正则").isNotEmpty();

        // 声明可以写在任何模块里（进销存那三个的声明在 shop-app/invbridge，
        // 因为 shop-inventory 刻意不依赖 shop-job-api —— 它要能独立交付）
        StringBuilder allSources = new StringBuilder();
        for (Path module : Files.list(BACKEND).filter(Files::isDirectory).toList()) {
            Path src = module.resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (var files = Files.walk(src)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    allSources.append(stripComments(Files.readString(f, StandardCharsets.UTF_8)));
                }
            }
        }
        String declared = allSources.toString();

        List<String> undeclared = locks.stream()
                .filter(n -> !declared.contains("JobDeclaration(\"" + n + "\"")
                        && !declared.contains("daily(\"" + n + "\""))
                .toList();
        assertThat(undeclared)
                .as("这些任务有 @Scheduled 却没有 JobDeclaration：%s\n"
                        + "  生产不带 worker profile，@EnableScheduling 因此不生效 ——\n"
                        + "  没进 job_definition 的任务**一次都不会跑**，且没有任何症状。\n"
                        + "  照 InventoryJobHandlers / InventoryReconJob 的样子补一个声明 bean。", undeclared)
                .isEmpty();
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
    @DisplayName("★★★ @EnableScheduling 只许有一处，且必须 @Profile(\"worker\")")
    void schedulingIsEnabledOnlyOnWorker() throws IOException {
        List<String> enablers = new ArrayList<>();
        for (Path module : Files.list(BACKEND).filter(Files::isDirectory).toList()) {
            Path src = module.resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (var files = Files.walk(src)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = stripComments(Files.readString(f, StandardCharsets.UTF_8));
                    if (code.contains("@EnableScheduling")) {
                        enablers.add(f.getFileName() + (code.contains("@Profile(\"worker\")") ? "" : " ← 没有 worker 门"));
                    }
                }
            }
        }
        assertThat(enablers)
                .as("@EnableScheduling 出现在：%s", enablers)
                .hasSize(1);
        assertThat(enablers.get(0)).doesNotContain("没有 worker 门");
    }

    @Test
    @DisplayName("★★ 遗留的 @Scheduled 触发器要挂 shop.job.enabled —— 但这道闸已经比从前弱")
    void legacyScheduledTriggersAreGated() throws IOException {
        List<String> ungated = new ArrayList<>();
        jobSources().forEach((name, text) -> {
            if (!text.contains("shop.job.enabled")) {
                ungated.add(name);
            }
        });
        assertThat(ungated)
                .as("""
                        这些还带 @Scheduled 的任务类没有 shop.job.enabled 的门：%s

                          **这条约定 2026-08-27 变过，变弱了，说明白比改绿重要。**

                          从前这里要求的是 @Profile("worker")，两道闸互相独立：
                          生产跑 api,ops，既没有 worker profile、也没打开 shop.job。

                          拆出独立调度器之后，业务系统必须**持有任务体**才能被调，
                          于是这些类改挂 shop.job.enabled —— 而生产现在正是 enabled=true。
                          也就是说：**只剩 @EnableScheduling 那一道闸**（上一条用例守着它）。

                          真正的收尾是把这些遗留的 @Scheduled 触发器摘掉 —— 任务的排期
                          已经在 job_definition 里，两套排期并存本身就是一个隐患：
                          worker profile 一旦打开，同一个任务会被 @Scheduled 和
                          JobRegistry 各排一遍。摘除是独立的一件事，不能夹在部署里做。""", ungated)
                .isEmpty();
    }
}
