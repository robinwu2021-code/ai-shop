package ai.neargo.shop.channel.media;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.PutObjectRequest;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CosMediaVisibilityTest {
    @Test void uploadedObjectsUseTheDeclaredPublicOrPrivateAcl() {
        var store = new CosMediaStore("id", "key", "ap-guangzhou", "test-1300000000", "", "");
        ((COSClient) ReflectionTestUtils.getField(store, "cos")).shutdown();
        var client = mock(COSClient.class);
        ReflectionTestUtils.setField(store, "cos", client);
        var keys = List.of("M/S/goods/202611/a.png", "_USER/U/avatar/202611/a.png",
                "_USER/U/evidence/review/202611/a.png", "M/legacy.png",
                "M/S/qual/202611/a.png", "M/S/aftersale/202611/a.png",
                "M/S/mallpay/202611/a.png", "M/S/reports/202611/a.csv");
        for (String key : keys) store.put(key, new ByteArrayInputStream(new byte[]{1}), 1, "image/png");
        var requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client, times(keys.size())).putObject(requests.capture());
        for (int i = 0; i < keys.size(); i++) {
            assertThat(requests.getAllValues().get(i).getKey()).isEqualTo(keys.get(i));
            assertThat(requests.getAllValues().get(i).getCannedAcl())
                    .isEqualTo(i < 4 ? CannedAccessControlList.PublicRead : CannedAccessControlList.Private);
        }
    }
}
