package ai.neargo.shop.printing.service;

import ai.neargo.shop.print.api.PrintScope;
import ai.neargo.shop.printing.entity.PrnDevice;
import ai.neargo.shop.printing.entity.PrnJob;
import ai.neargo.shop.printing.mapper.PrintMappers.DeviceMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.JobMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Component;

/** Canonical device lock plus the entire outstanding ledger, including pre-upgrade uncertainty. */
@Component
public class PrintDeviceGate {
    private final DeviceMapper devices;
    private final JobMapper jobs;
    public PrintDeviceGate(DeviceMapper devices, JobMapper jobs) { this.devices=devices; this.jobs=jobs; }
    public void lockStore(PrintScope scope) {
        devices.selectList(new QueryWrapper<PrnDevice>().eq("tenant_no",scope.tenantNo())
                .eq("entity_no",scope.entityNo()).eq("store_no",scope.storeNo())
                .orderByAsc("device_no").last("FOR UPDATE"));
    }
    public PrnDevice lock(PrintScope scope, String deviceNo) {
        var row=devices.selectOne(new QueryWrapper<PrnDevice>()
                .eq("tenant_no",scope.tenantNo()).eq("entity_no",scope.entityNo())
                .eq("store_no",scope.storeNo()).eq("device_no",deviceNo).last("FOR UPDATE"));
        if(row==null) throw new ai.neargo.shop.print.api.PrintFailure("print.notFound");
        return row;
    }
    private QueryWrapper<PrnJob> outstanding(PrnDevice device) {
        return new QueryWrapper<PrnJob>().eq("tenant_no",device.getTenantNo())
                .eq("entity_no",device.getEntityNo()).eq("store_no",device.getStoreNo())
                .eq("device_no",device.getDeviceNo()).in("status","SENT","UNKNOWN")
                .isNotNull("current_attempt_no").isNull("execution_released_at");
    }
    public boolean available(PrnDevice device) {
        return "ACTIVE".equals(device.getStatus()) && device.getActiveAttemptNo()==null
                && jobs.selectCount(outstanding(device))==0;
    }
    public void occupy(PrnDevice device,String job,String attempt,long fence) {
        // Caller holds this row lock; ledger checks run before inserting the new in-flight attempt.
        if(!"ACTIVE".equals(device.getStatus()) || device.getActiveAttemptNo()!=null)
            throw new ai.neargo.shop.print.api.PrintFailure("print.deviceBusy");
        device.setActiveJobNo(job); device.setActiveAttemptNo(attempt); device.setActiveFenceEpoch(fence);
        if(devices.updateById(device)!=1) throw new ai.neargo.shop.print.api.PrintFailure("print.conflict");
    }
    public void releaseIfCurrent(PrintScope scope,String deviceNo,String attempt,long fence) {
        var device=lock(scope,deviceNo);
        if(device.getActiveFenceEpoch()!=null && device.getActiveFenceEpoch()==fence) release(scope,deviceNo,attempt);
    }
    public void release(PrintScope scope,String deviceNo,String attempt) {
        var device=lock(scope,deviceNo);
        if(!attempt.equals(device.getActiveAttemptNo())) return;
        jobs.update(null,new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PrnJob>()
                .eq("tenant_no",scope.tenantNo()).eq("device_no",deviceNo).eq("current_attempt_no",attempt)
                .set("execution_released_at",java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
        jobs.releaseRuntime(scope.tenantNo(),scope.entityNo(),deviceNo,attempt);
        var next=jobs.selectOne(outstanding(device).orderByAsc("id").last("LIMIT 1 FOR UPDATE"));
        devices.update(null,new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PrnDevice>()
                .eq("tenant_no",scope.tenantNo()).eq("device_no",deviceNo).eq("active_attempt_no",attempt)
                .set("active_job_no",next==null?null:next.getJobNo())
                .set("active_attempt_no",next==null?null:next.getCurrentAttemptNo()));
    }
}
