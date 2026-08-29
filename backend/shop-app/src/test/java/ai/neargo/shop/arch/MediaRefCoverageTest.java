package ai.neargo.shop.arch;

import ai.neargo.shop.media.MediaRefColumn;
import ai.neargo.shop.media.MediaRefSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>schema 里每一个装图片地址的列，都必须有人认领。</b>
 *
 * <p>这是整套回收机制里最要紧的一道闸。回收的判据是「还有没有人引用」，
 * 而 {@link MediaRefSource} 的声明就是「谁算引用」的全集 ——
 * <b>漏一列的后果不是少删几张，是那一列引用的图全被判成孤儿然后删掉</b>。
 *
 * <p>而漏登记是件几乎必然会发生的事：加一张带 {@code cover} 的新表时，
 * 没有任何东西会提醒你还有一份清单要改。所以让它构建失败。
 *
 * <p><b>本测试读的是迁移 SQL 而不是 H2 的 schema-test.sql</b>：后者在转换时
 * 丢掉了 {@code COMMENT}，而「注释里提到图」正是识别可疑列的一半依据。
 */
class MediaRefCoverageTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** 列名一看就是放图的。 */
    private static final Set<String> IMAGE_NAMES = Set.of(
            "cover", "images", "image_url", "logo", "avatar", "qualifications");

    /** 自由文本列：地址可能嵌在正文里。 */
    private static final Set<String> TEXT_NAMES = Set.of("content");

    /**
     * 明确<b>不是</b>上传资产的列，连同理由。
     *
     * <p>之所以要求写理由而不是只列名字：这份名单是用来豁免检查的，
     * 而没有理由的豁免半年后就没人敢动，也没人记得当初为什么加。
     */
    private static final Map<String, String> NOT_MEDIA = Map.of(
            "sys_function.icon", "菜单图标是前端内置图标名（如 shop / user），不是上传的文件",
            "mch_store_audit.kind", "枚举值 BANNER/NOTICE，注释里提到「店招图」只是在解释枚举含义",
            "sys_media_asset.last_ref_desc", "记账表自己的人话描述列，内容是「商品 G0012 · 主图」这种标签",
            // 下面三条都是被「注释里含『图』就算可疑」这条启发式扫进来的 ——
            // 判据宁可多报也不漏报是对的，但这三列确实与上传资产无关。
            "usr_address.region", "省市区整串（如「浙江省杭州市西湖区」）。注释里的「图」出自"
                    + "「地图选点回填」—— 说的是这一串从哪来，不是说它装着一张图",
            "msg_template.content", "通知模板正文，含 {占位符} 的纯文本；列名 content 命中的是"
                    + "「长文本列」那半条启发式，不是图片",
            "msg_ticket.content", "工单正文，用户打字写的一段话。同上，命中的是列名不是内容");

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE(?:\\s+IF NOT EXISTS)?\\s+([a-z_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN =
            Pattern.compile("^\\s+([a-z_]+)\\s+(VARCHAR\\(\\d+\\)|TEXT|JSON)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "ALTER TABLE\\s+([a-z_]+)\\s+ADD COLUMN\\s+([a-z_]+)\\s+(VARCHAR\\(\\d+\\)|TEXT|JSON)(.*)$",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("schema 里的图片列必须都被 MediaRefSource 声明，否则那批图会被判成孤儿")
    void everyImageColumnIsClaimed() throws Exception {
        Set<String> declared = new LinkedHashSet<>();
        for (MediaRefSource source : sources()) {
            for (MediaRefColumn c : source.columns()) {
                declared.add(c.table() + "." + c.column());
            }
        }

        List<String> unclaimed = new ArrayList<>();
        for (String candidate : scanSchema()) {
            if (declared.contains(candidate) || NOT_MEDIA.containsKey(candidate)) {
                continue;
            }
            unclaimed.add(candidate);
        }

        assertThat(unclaimed)
                .as("这些列看着装的是图片地址，但没有任何 MediaRefSource 声明它们。"
                        + "回收扫描不会把它们算作引用，于是它们指向的图会被判成孤儿并进待回收清单。"
                        + "要么在对应模块的 MediaRefSource 里补一行，"
                        + "要么加进本测试的 NOT_MEDIA 并写清为什么它不是上传资产")
                .isEmpty();
    }

    @Test
    @DisplayName("NOT_MEDIA 里不能留下已经不存在的列")
    void exemptionsMustStillExist() throws Exception {
        Set<String> all = scanSchema();
        List<String> stale = NOT_MEDIA.keySet().stream().filter(k -> !all.contains(k)).sorted().toList();

        assertThat(stale)
                .as("这些豁免项在 schema 里已经找不到了。留着它们只会让下一个人以为"
                        + "「这一列检查过了」——而它根本不存在。删掉")
                .isEmpty();
    }

    /** 扫迁移 SQL，挑出「看着像装图片地址」的列。 */
    private static Set<String> scanSchema() throws IOException {
        Set<String> found = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
                String table = null;
                for (String line : Files.readAllLines(f)) {
                    Matcher t = CREATE_TABLE.matcher(line);
                    if (t.find()) {
                        table = t.group(1).toLowerCase(Locale.ROOT);
                    }
                    Matcher add = ADD_COLUMN.matcher(line);
                    if (add.find()) {
                        collect(found, add.group(1), add.group(2), add.group(4));
                        continue;
                    }
                    Matcher c = COLUMN.matcher(line);
                    if (c.find() && table != null) {
                        collect(found, table, c.group(1), c.group(3));
                    }
                }
            }
        }
        return found;
    }

    private static void collect(Set<String> into, String table, String column, String rest) {
        String col = column.toLowerCase(Locale.ROOT);
        // 注释里提到「图」的也算可疑 —— 列名不一定叫 cover，但注释骗不了人
        boolean commentMentionsImage = rest != null && rest.contains("图");
        if (IMAGE_NAMES.contains(col) || TEXT_NAMES.contains(col) || commentMentionsImage) {
            into.add(table.toLowerCase(Locale.ROOT) + "." + col);
        }
    }

    /**
     * 找出所有实现。<b>不注入 Spring 容器</b>：这是一条构建期的结构规则，
     * 不该为它启一个应用上下文 —— 也不该因为某个域的 bean 起不来就顺带失效。
     */
    private static List<MediaRefSource> sources() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(MediaRefSource.class));
        List<MediaRefSource> found = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("ai.neargo.shop")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            if (type.isInterface()) {
                continue;
            }
            found.add((MediaRefSource) type.getDeclaredConstructor().newInstance());
        }
        assertThat(found).as("一个 MediaRefSource 实现都没扫到，这条守卫等于没跑").isNotEmpty();
        return found;
    }
}
