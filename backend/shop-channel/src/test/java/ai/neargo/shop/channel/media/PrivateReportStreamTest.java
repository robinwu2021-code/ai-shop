package ai.neargo.shop.channel.media;

import java.io.ByteArrayInputStream;
import ai.neargo.shop.common.BizException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivateReportStreamTest {
    @TempDir Path root;
    @Test void privateStreamReadsOwnedBytesAndRejectsPublicOrTraversalKeys() throws Exception {
        var store=new LocalDiskMediaStore(root.toString(),"/uploads","/media","test-secret");
        String key="E1/_REPORT/reports/REXP1/1_random.csv";byte[] bytes={1,2,3};
        store.put(key,new ByteArrayInputStream(bytes),bytes.length,"text/csv");
        try(var stream=store.openPrivate(key)) { assertThat(stream.readAllBytes()).isEqualTo(bytes); }
        assertThatThrownBy(()->store.openPrivate("E1/S1/goods/image.png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->store.openPrivate("E1/S1/reports/../../../../outside.csv")).isInstanceOf(BizException.class);
    }
}
