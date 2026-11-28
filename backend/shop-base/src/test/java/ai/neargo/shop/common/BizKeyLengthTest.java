package ai.neargo.shop.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * 业务编号最长 32 位：新库（平台基础库等）的编号列是 {@code varchar(32)}。
 *
 * <p>平台表的 H2 测试库把这些列放宽到了 64（测试数据惯用 UUID 当编号，见 gen-platform-test-schema.py），
 * 所以测试库挡不住超长的编号 —— 这条测试替它挡：每个前缀生成的编号都不超过 32 位。
 */
class BizKeyLengthTest {

    @Test
    void everyPrefixFitsTheNewDatabaseNumberColumns() throws IllegalAccessException {
        int checked = 0;
        for (var field : BizKey.class.getDeclaredFields()) {
            int m = field.getModifiers();
            if (field.getType() != String.class || !Modifier.isStatic(m) || !Modifier.isFinal(m) || !Modifier.isPublic(m)) {
                continue;
            }
            String prefix = (String) field.get(null);
            String no = BizKey.next(prefix);
            assertTrue(no.length() <= 32, field.getName() + " 生成的编号 " + no.length() + " 位，超过新库编号列的 32 位：" + no);
            checked++;
        }
        assertTrue(checked > 50, "一个前缀都没扫到？");
    }
}
