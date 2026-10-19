package ai.neargo.shop.pay.points.admin.impl;
import ai.neargo.shop.pay.points.admin.*;
import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.pay.mapper.points.PointsAdjustmentMapper;
import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
@Service
public class PointsAdjustmentServiceImpl implements PointsAdjustmentService {
 private final PointsOperatorJournal journal; private final PointsAdjustmentMapper rows;
 private final PointsReadStore read; private final PointsPostingService posting;
 private final ObjectMapper json=new ObjectMapper();
 public PointsAdjustmentServiceImpl(PointsOperatorJournal journal,PointsAdjustmentMapper rows,PointsReadStore read,PointsPostingService posting) {
  this.journal=journal;this.rows=rows;this.read=read;this.posting=posting;
 }
 public OperatorResult submit(Submission r,String actor) {
  require(r!=null && text(r.adjustmentNo(),40) && text(r.typeCode(),40) && text(r.userNo(),40) && r.direction()!=null && r.points()>0 && text(r.reason(),1000),"POINTS_ADJUSTMENT_INPUT_INVALID");
  return journal.apply("USER_ADJUST_SUBMIT",r.requestId(),r.typeCode(),actor,r.reason(),r,()-> {
   var type=read.type(r.typeCode()).orElseThrow(); type.rate().requireAligned(r.points());
   require(type.status()!=PointType.Status.RETIRED && (r.direction()!=Direction.INCREASE || type.status()==PointType.Status.ACTIVE),"POINTS_TYPE_NOT_ACTIVE");
   require(rows.lock(r.adjustmentNo())==null,"POINTS_ADJUSTMENT_NUMBER_CONFLICT");
   rows.insert(r.adjustmentNo(),r.typeCode(),actor,json.writeValueAsString(r)); return r.adjustmentNo();
  });
 }
 public OperatorResult review(Review r,String actor) {
  require(r!=null && text(r.adjustmentNo(),40) && r.expectedVersion()>=0 && text(r.reason(),1000),"POINTS_ADJUSTMENT_INPUT_INVALID");
  return journal.apply("USER_ADJUST_REVIEW",r.requestId(),r.typeCode(),actor,r.reason(),r,()-> {
   var row=rows.lock(r.adjustmentNo());
   require(row!=null && r.typeCode().equals(row.get("type_code")),"POINTS_ADJUSTMENT_UNKNOWN");
   require(!actor.equals(row.get("submitted_by")),"POINTS_SELF_APPROVE");
   require("SUBMITTED".equals(row.get("state")),"POINTS_ADJUSTMENT_STATE_CONFLICT");
   require(number(row,"version")==r.expectedVersion(),"POINTS_ADJUSTMENT_REVISION_CONFLICT");
   Submission original=json.readValue((String)row.get("payload_json"),Submission.class);
   require(original.typeCode().equals(r.typeCode()) && original.adjustmentNo().equals(r.adjustmentNo()),"POINTS_ADJUSTMENT_PAYLOAD_CONFLICT");
   require(rows.decide(r.adjustmentNo(),r.expectedVersion(),r.approve()?"APPROVED":"REJECTED",actor)==1,"POINTS_ADJUSTMENT_CONFLICT");
   if(r.approve()) {
    var type=read.type(original.typeCode()).orElseThrow(); long p=original.points();
    require(type.status()!=PointType.Status.RETIRED && (original.direction()!=Direction.INCREASE || type.status()==PointType.Status.ACTIVE),"POINTS_TYPE_NOT_ACTIVE");
    type.rate().requireAligned(p);
    var user=new AccountKey(type.typeCode(),HolderType.USER,original.userNo(),Bucket.AVAILABLE);
    boolean add=original.direction()==Direction.INCREASE;
    var moves=add?List.of(new PointsMove(new AccountKey(type.typeCode(),HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE),-p),new PointsMove(user,p)):
      List.of(new PointsMove(user,-p),new PointsMove(new AccountKey(type.typeCode(),HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE),p));
    var funds=add?List.of(new FundMove(FundBucket.U,-type.rate().toMinor(p)),new FundMove(FundBucket.C,type.rate().toMinor(p))):List.of(new FundMove(FundBucket.C,-type.rate().toMinor(p)),new FundMove(FundBucket.U,type.rate().toMinor(p)));
    posting.post(new Voucher("ADJUST:"+original.adjustmentNo(),type.typeCode(),type.currency(),type.rate(),moves,funds,List.of(),0,List.of(),json.writeValueAsString(original)));
   }
   return r.adjustmentNo();
  });
 }
 public Page list(String type,long after,int limit) {
  require(text(type,40) && after>=0 && limit>=1 && limit<=100,"POINTS_PAGE_INVALID");
  var result=rows.page(type,after,limit).stream().map(row->{
   var r=json.readValue((String)row.get("payload_json"),Submission.class);
   return new View(number(row,"id"),r.adjustmentNo(),r.typeCode(),r.userNo(),r.direction(),r.points(),r.reason(),(String)row.get("state"),(String)row.get("submitted_by"),(String)row.get("approved_by"),number(row,"version"));
  }).toList();
  return new Page(result,result.isEmpty()?after:result.getLast().id(),result.size()==limit);
 }
 private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
 private boolean text(String value,int max){return value!=null && !value.isBlank() && value.length()<=max;}
 private void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
