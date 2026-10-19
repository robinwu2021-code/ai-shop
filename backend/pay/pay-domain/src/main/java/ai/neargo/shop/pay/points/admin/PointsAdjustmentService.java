package ai.neargo.shop.pay.points.admin;
import java.util.List;
import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
public interface PointsAdjustmentService {
 enum Direction { INCREASE, DECREASE }
 record Submission(String requestId,String adjustmentNo,String typeCode,String userNo,Direction direction,long points,String reason) {}
 record Review(String requestId,String adjustmentNo,String typeCode,long expectedVersion,boolean approve,String reason) {}
 record View(long id,String adjustmentNo,String typeCode,String userNo,Direction direction,long points,String reason,String state,String submittedBy,String approvedBy,long version) {}
 record Page(List<View> rows,long next,boolean hasMore) {}
 OperatorResult submit(Submission request,String actor);
 OperatorResult review(Review request,String actor);
 Page list(String typeCode,long after,int limit);
}
