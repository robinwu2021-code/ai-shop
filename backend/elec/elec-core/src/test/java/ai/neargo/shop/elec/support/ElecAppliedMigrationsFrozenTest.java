package ai.neargo.shop.elec.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>已经在生产执行过的元器件迁移，一个字节都不许改。</b>
 *
 * <p>Flyway 启动时按校验和比对历史表（{@code elc_flyway_history}）：改过的文件对不上，服务直接起不来。
 * 即便跳过校验，改动也永远到不了生产库 —— 那个版本号在生产上已经执行完了。
 *
 * <p>这条测试是被催出来的：elec-svc 2026-09-30 08:21 首次上线后一个小时内，V1 被改了两次 ——
 * 一次只动了一句列注释（2ae866260），一次往里面加了两列（1afe17f2d）。两次都是全量测试全绿、
 * 本地起服务正常（本地库每次都是新建的），只有下一次上生产才会炸。
 *
 * <p><b>要改表结构：新开一个 {@code V&lt;下一个号&gt;__*.sql}</b>，用 {@code ALTER TABLE}。
 * 要改注释也一样。新版本上了生产之后，把它的指纹也加进下面这张表。
 */
class ElecAppliedMigrationsFrozenTest {

    /** 文件 → SHA-256。只收**生产已执行**的版本（查法：{@code select version,checksum from ai_shop_elec.elc_flyway_history}） */
    private static final Map<String, String> APPLIED = Map.of(
            "db/elec/V1__elec_baseline.sql", "5ddec427261d4d1427b8a178fb2a0b9be328b270adeafb5a07077e63cd192166");

    @Test
    @DisplayName("生产执行过的迁移没被改过 —— 要改表就新开一个版本号")
    void appliedMigrationsAreFrozen() throws IOException, NoSuchAlgorithmException {
        for (Map.Entry<String, String> e : APPLIED.entrySet()) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(e.getKey())) {
                assertThat(in).as("找不到 %s —— 已执行的迁移也不能删或改名", e.getKey()).isNotNull();
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
                assertThat(sha)
                        .as("%s 在生产已经执行过，内容被改了。Flyway 会校验不过、elec-svc 起不来，"
                                + "而且改动永远到不了生产库。把改动挪进新的 V<n>__*.sql（ALTER TABLE），这个文件还原", e.getKey())
                        .isEqualTo(e.getValue());
            }
        }
    }
}
