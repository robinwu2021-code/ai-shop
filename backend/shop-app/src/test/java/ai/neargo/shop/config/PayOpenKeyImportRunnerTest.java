package ai.neargo.shop.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.secret.OpenKeyImport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 一次性导入动作按配置分派（开发计划 M3.10）；认不出的动作什么都不做，也不拖垮启动 */
class PayOpenKeyImportRunnerTest {

    @Test
    @DisplayName("★★ upload / enable / status 各走各的，app 列表去空格；认不出的动作不碰密钥模块")
    void dispatch() {
        OpenKeyImport upload = mock(OpenKeyImport.class);
        when(upload.upload(List.of("APP-A", "APP-B"), "ops-alice")).thenReturn(List.of("ok"));
        new PayOpenKeyImportRunner(upload, "upload", " APP-A , APP-B ", " ops-alice ").run(null);
        verify(upload).upload(List.of("APP-A", "APP-B"), "ops-alice");

        OpenKeyImport enable = mock(OpenKeyImport.class);
        when(enable.enable(List.of("APP-A"), "ops-bob")).thenReturn(List.of("ok"));
        new PayOpenKeyImportRunner(enable, "enable", "APP-A", "ops-bob").run(null);
        verify(enable).enable(List.of("APP-A"), "ops-bob");

        OpenKeyImport unknown = mock(OpenKeyImport.class);
        new PayOpenKeyImportRunner(unknown, "delete-everything", "APP-A", "x").run(null);
        verifyNoInteractions(unknown);
    }
}
