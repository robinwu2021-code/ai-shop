package ai.neargo.shop.pay.points.admin;
import java.util.List;
import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
public interface PointsAdjustmentService {
 enum Direction { INCREASE, DECREASE }
 record Submission(String requestId,String adjustmentNo,String typeCode,String userNo,Direction direction,long points,String reason) {}
 record Review(String requestId,String adjustmentNo,String typeCode,long expectedVersion,boolean approve,String reason) {}
 record View(long id,String adjustmentNo,String typeCode,String userNo,Direction direction,long points,String reason,String state,String submittedBy,String approvedBy,long version,Long submittedAt,Long reviewedAt) {}
 record Page(List<View> rows,long next,boolean hasMore) {}
 OperatorResult submit(Submission request,String actor);
 OperatorResult review(Review request,String actor);
 /** 按单号取一条（审批送审、生效用；方案-第一批审批工作流 T8）。 */
 java.util.Optional<View> adjustment(String adjustmentNo);
 /** 待审的调账单，最多 limit 条（补送审批用）。 */
 java.util.List<View> submitted(int limit);
 Page list(String typeCode,long after,int limit);
}
