package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.points.admin.PointsAdjustmentService;
import ai.neargo.shop.pay.points.admin.PointsOperatorJournal;
import ai.neargo.shop.pay.points.admin.impl.PointsAdjustmentServiceImpl;
import ai.neargo.shop.pay.mapper.points.PointsAdjustmentMapper;
import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.function.Supplier;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PointsUserAdjustmentTest {
    private final PointsAdjustmentMapper rows=mock(PointsAdjustmentMapper.class);
    private final PointsOperatorJournal journal=mock(PointsOperatorJournal.class);
    private final PointsReadStore read=mock(PointsReadStore.class);
    private final PointsPostingService posting=mock(PointsPostingService.class);
    private PointsAdjustmentService service() {
        when(journal.apply(anyString(),anyString(),anyString(),anyString(),anyString(),any(),any()))
                .thenAnswer(i -> { ((Supplier<String>)i.getArgument(6)).get(); return null; });
        when(read.type("T")).thenReturn(Optional.of(new PointType("T","CN","CNY",0,new RedeemRate(1,1),10000,30,PointType.Status.ACTIVE)));
        return new PointsAdjustmentServiceImpl(journal,rows,read,posting);
    }
    private void original(String direction) {
        when(rows.lock("A")).thenReturn(new HashMap<>(Map.of("type_code","T","submitted_by","MAKER","state","SUBMITTED","version",0L,
            "payload_json","{\"requestId\":\"S\",\"adjustmentNo\":\"A\",\"typeCode\":\"T\",\"userNo\":\"U\",\"direction\":\""+direction+"\",\"points\":10,\"reason\":\"correction\"}")));
        when(rows.decide(anyString(),anyLong(),anyString(),anyString())).thenReturn(1);
    }
    @Test void cannot_review_own_submission() {
        original("INCREASE"); var s=service();
        assertThatThrownBy(() -> s.review(new PointsAdjustmentService.Review("R","A","T",0,true,"ok"),"MAKER"))
            .hasMessageContaining("SELF_APPROVE"); verifyNoInteractions(posting);
    }
    @Test void stale_revision_cannot_post() {
        original("INCREASE"); var s=service();
        assertThatThrownBy(() -> s.review(new PointsAdjustmentService.Review("R","A","T",1,true,"ok"),"CHECKER"))
            .hasMessageContaining("REVISION"); verifyNoInteractions(posting);
    }
    @Test void increase_consumes_u_and_credits_available() {
        original("INCREASE"); var s=service();
        s.review(new PointsAdjustmentService.Review("R","A","T",0,true,"ok"),"CHECKER");
        var v=ArgumentCaptor.forClass(Voucher.class); verify(posting).post(v.capture());
        v.getValue().validateV1ToV4();
        assertThat(v.getValue().fundMoves()).containsExactly(new FundMove(FundBucket.U,-10),new FundMove(FundBucket.C,10));
        assertThat(v.getValue().pointsMoves()).contains(new PointsMove(new AccountKey("T",HolderType.USER,"U",Bucket.AVAILABLE),10));
    }
    @Test void decrease_only_available_releases_budget_to_issue() {
        original("DECREASE"); var s=service();
        s.review(new PointsAdjustmentService.Review("R","A","T",0,true,"ok"),"CHECKER");
        var v=ArgumentCaptor.forClass(Voucher.class); verify(posting).post(v.capture()); v.getValue().validateV1ToV4();
        assertThat(v.getValue().fundMoves()).containsExactly(new FundMove(FundBucket.C,-10),new FundMove(FundBucket.U,10));
        assertThat(v.getValue().pointsMoves()).containsExactly(new PointsMove(new AccountKey("T",HolderType.USER,"U",Bucket.AVAILABLE),-10),
            new PointsMove(new AccountKey("T",HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE),10));
    }
    @Test void rejected_application_does_not_post() {
        original("INCREASE"); var s=service();
        s.review(new PointsAdjustmentService.Review("R","A","T",0,false,"reject"),"CHECKER");
        verifyNoInteractions(posting); verify(rows).decide("A",0,"REJECTED","CHECKER");
    }
    @Test void failed_cas_cannot_post() {
        original("INCREASE"); var s=service();
        when(rows.decide(anyString(),anyLong(),anyString(),anyString())).thenReturn(0);
        assertThatThrownBy(() -> s.review(new PointsAdjustmentService.Review("R","A","T",0,true,"ok"),"CHECKER"))
            .hasMessageContaining("CONFLICT"); verifyNoInteractions(posting);
    }
    @Test void insufficient_budget_failure_is_not_swallowed() {
        original("INCREASE"); var s=service();
        when(posting.post(any())).thenThrow(new IllegalArgumentException("INSUFFICIENT_U"));
        assertThatThrownBy(() -> s.review(new PointsAdjustmentService.Review("R","A","T",0,true,"ok"),"CHECKER"))
            .hasMessageContaining("INSUFFICIENT_U");
    }
}
