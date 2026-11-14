package ai.neargo.shop.printing;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.printing.entity.PrnJob;
import ai.neargo.shop.printing.center.support.PrintViews;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class UnifiedPrintProjectionTest {
    private static final PrintActor ACTOR=new PrintActor("MAIN","E","S","MERCHANT","A","KEY",
            Set.of("biz:print:reprint","biz:print:extra","biz:print:submit","biz:print:resolve"));
    @Test void receiptAcceptanceIsNotPhysicalCompletion() {
        var row=new PrnJob();
        row.setStatus("ACCEPTED");
        assertThat(row.getLegacyStatus()).isEqualTo("SENT");
        assertThat(row.getStatus()).isEqualTo("ACCEPTED");
        row.setStatus("COMPLETED");
        assertThat(row.getLegacyStatus()).isEqualTo("SUCCESS");
        assertThat(row.getStatus()).isEqualTo("COMPLETED");
    }
    @Test void legacyHistoryHasAStableReadOnlyProjectionWithoutRichSnapshots() {
        var row=new PrnJob(); row.setJobNo("OLD"); row.setDeviceNo("DEVICE");row.setStoreNo("S");
        row.setScene("KITCHEN");row.setLegacyStatus("SUCCESS");row.setVersion(7L);
        row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(row.getCreatedAt());
        row.setCopyIndex(1);row.setRequired(1);row.setPriority(0);row.setRetryCount(0);
        var view=PrintViews.job(row,ACTOR);
        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.printerNo()).isEqualTo("DEVICE");
        assertThat(view.allowedActions()).isEmpty();
        assertThat(view.maxRetryCount()).isZero();
        assertThat(row.getRichDocumentSnapshot()).isNull();
    }
    @Test void revisionUsesTheCanonicalOptimisticVersion() {
        var row=new PrnJob();row.setVersion(4L);
        assertThat(row.getRevision()).isEqualTo(4L);
        row.setRevision(5L);
        assertThat(row.getVersion()).isEqualTo(5L);
    }
}
