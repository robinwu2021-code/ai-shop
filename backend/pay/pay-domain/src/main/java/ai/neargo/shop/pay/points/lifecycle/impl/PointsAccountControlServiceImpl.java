package ai.neargo.shop.pay.points.lifecycle.impl;

import ai.neargo.shop.pay.points.policy.PointsDigests;

import ai.neargo.shop.pay.points.lifecycle.PointsAccountControlService;
import ai.neargo.shop.pay.points.policy.SwitchPolicy;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Scope;
import ai.neargo.shop.pay.points.store.LifecycleStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsFactStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional("payTxManager")
public class PointsAccountControlServiceImpl implements PointsAccountControlService {
    private final LifecycleStore store;
    private final PointsConfigurationStore config;
    private final PointsFactStore facts;
    private final ObjectMapper json=new ObjectMapper();
    public PointsAccountControlServiceImpl(LifecycleStore store,PointsConfigurationStore config,PointsFactStore facts) {
        this.store=store; this.config=config; this.facts=facts;
    }
    @Override public void freeze(Command c) {
        require(c!=null && nonblank(c.eventNo()) && nonblank(c.typeCode()) && nonblank(c.userNo())
                && nonblank(c.actor()) && nonblank(c.reason()) && c.at()>=0,"POINTS_FREEZE_INPUT_INVALID");
        config.lockTypeAndPool(c.typeCode());
        String body=json.writeValueAsString(c);
        var prior=facts.lockInbox(c.eventNo());
        if (prior!=null) { require(body.equals(prior.get("payload_json")),"POINTS_EVENT_CONFLICT"); return; }
        var account=store.lockUser(c.typeCode(),c.userNo());
        require(account!=null,"POINTS_ACCOUNT_UNKNOWN");
        var control=store.lockSwitch("FREEZE",SwitchPolicy.key(Scope.USER,c.typeCode(),c.userNo()));
        if (control!=null) {
            var previous=json.readValue((String)control.get("payload_json"),Command.class);
            require(c.at()>=previous.at(),"POINTS_FREEZE_TIME_CONFLICT");
        }
        store.freeze(c,body,((Number)account.get("version")).longValue(),
                ((Number)account.get("frozen")).intValue()!=0);
        facts.recordEvent(c.typeCode(),c.eventNo(),PointsDigests.sha256(body),body,"ACCOUNT_CONTROLLED");
    }
    private boolean nonblank(String value) { return value!=null && !value.isBlank(); }
    private void require(boolean condition,String reason) {
        if (!condition) { throw new IllegalArgumentException(reason); }
    }
}
