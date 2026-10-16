package ai.neargo.shop.pay.points.lifecycle.impl;

import ai.neargo.shop.pay.points.event.PointsEventQueue;
import ai.neargo.shop.pay.points.event.PointsEventQueue.Kind;
import ai.neargo.shop.pay.points.event.PointsEventQueue.Message;
import ai.neargo.shop.pay.points.lifecycle.ExpiryReminderService;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.policy.ExpiryPolicy;
import ai.neargo.shop.pay.points.store.LifecycleStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;

@Service
@Transactional("payTxManager")
public class ExpiryReminderServiceImpl implements ExpiryReminderService {
    private final LifecycleStore store;
    private final PointsConfigurationStore config;
    private final PointsReadStore read;
    private final PointsEventQueue queue;
    private final ObjectMapper json=new ObjectMapper();
    public ExpiryReminderServiceImpl(LifecycleStore store,PointsConfigurationStore config,
            PointsReadStore read,PointsEventQueue queue) {
        this.store=store; this.config=config; this.read=read; this.queue=queue;
    }
    @Override public boolean prepare(String type,String user,long now,int days) {
        if (now<0 || days<1 || days>30) { throw new IllegalArgumentException("POINTS_REMINDER_INPUT_INVALID"); }
        config.lockTypeAndPool(type);
        var account=store.lockUser(type,user);
        if (account==null || account.get("expire_at")==null || num(account,"available_points")==0
                || num(account,"frozen")!=0 || !read.enabled(type,user,null)) { return false; }
        long revision=num(account,"expiry_revision");
        if (account.get("reminded_revision")!=null && num(account,"reminded_revision")==revision) { return false; }
        long due=due(type,user,account,now);
        if (due<=now || due-now>Math.multiplyExact(days,86400000L)) { return false; }
        String event="REMIND:"+PointsKeys.position("N",type,user+":"+revision);
        var notice=new Reminder(type,user,revision,due,num(account,"available_points"),event);
        queue.enqueue(type,event,new Message(Kind.EXPIRY_REMINDER,json.writeValueAsString(notice)));
        store.markReminded(type,user,revision);
        return true;
    }
    @Override public boolean current(Reminder notice) {
        config.lockTypeAndPool(notice.typeCode());
        var account=store.lockUser(notice.typeCode(),notice.userNo());
        return account!=null && num(account,"expiry_revision")==notice.revision()
                && num(account,"available_points")>0 && num(account,"frozen")==0
                && read.enabled(notice.typeCode(),notice.userNo(),null)
                && due(notice.typeCode(),notice.userNo(),account,notice.expireAt())==notice.expireAt();
    }
    private long due(String type,String user,Map<String,Object> account,long now) {
        long active=account.get("last_active_at")==null?0:num(account,"last_active_at");
        return ExpiryPolicy.extend(num(account,"expire_at"),active,now,store.pauses(type,user,active,now));
    }
    private long num(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
}
