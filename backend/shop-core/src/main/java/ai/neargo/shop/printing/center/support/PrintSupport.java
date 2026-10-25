package ai.neargo.shop.printing.center.support;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.printing.center.persistence.entity.PrnRow;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.time.*;
import java.util.*;

public final class PrintSupport {
    private PrintSupport() {}

    public static int pageNumber(Integer value){int page=value==null?1:value;require(page>=1 && page<=1_000_000,"print.invalidPage");return page;}
    public static int pageSize(Integer value){int size=value==null?20:value;require(size>=1 && size<=100,"print.invalidPageSize");return size;}

    public static String no(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
    }

    public static PrintScope scope(PrintActor a) {
        return new PrintScope(a.tenantNo(), a.entityNo(), a.storeNo());
    }

    public static <T> QueryWrapper<T> scoped(PrintScope s) {
        return new QueryWrapper<T>()
                .eq("tenant_no", s.tenantNo())
                .eq("entity_no", s.entityNo())
                .eq("store_no", s.storeNo());
    }

    public static <T> com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<T> ownedUpdate(
            PrintScope s) {
        return new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<T>()
                .eq("tenant_no", s.tenantNo())
                .eq("entity_no", s.entityNo());
    }

    public static <T> QueryWrapper<T> owned(PrintScope s) {
        return new QueryWrapper<T>().eq("tenant_no", s.tenantNo()).eq("entity_no", s.entityNo());
    }

    public static <T extends PrnRow> T init(T r, PrintScope s) {
        r.setTenantNo(s.tenantNo());
        r.setEntityNo(s.entityNo());
        r.setRevision(0L);
        r.setCreatedAt(now());
        r.setUpdatedAt(now());
        return r;
    }

    public static ai.neargo.shop.printing.entity.PrnJob init(ai.neargo.shop.printing.entity.PrnJob r, PrintScope s) {
        base(r, s); r.setEntityNo(s.entityNo()); r.setRevision(0L); return r;
    }
    public static ai.neargo.shop.printing.entity.PrnAttempt init(ai.neargo.shop.printing.entity.PrnAttempt r, PrintScope s) {
        base(r, s); r.setEntityNo(s.entityNo()); r.setRevision(0L); return r;
    }
    private static void base(ai.neargo.shop.common.BaseEntity r, PrintScope s) {
        r.setTenantNo(s.tenantNo()); r.setVersion(0L); r.setDeleted(0);
        r.setCreatedAt(now()); r.setUpdatedAt(now()); r.setCreatedBy("PRINT_OWNER"); r.setUpdatedBy("PRINT_OWNER");
    }
    public static void revision(ai.neargo.shop.printing.entity.PrnJob row, Long expected) {
        require(row != null, "print.notFound");
        require(expected != null && expected.equals(row.getRevision()), "print.revisionConflict");
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    public static void require(boolean valid, String key) {
        if (!valid) throw new PrintFailure(key);
    }

    public static void access(PrintScope scope, PrintActor actor, Set<String> fields) {
        require(scope.equals(scope(actor)), "print.forbidden");
        require(actor.dataScopes().containsAll(fields), "print.fieldForbidden");
    }

    public static void revision(PrnRow row, Long expected) {
        require(row != null, "print.notFound");
        require(expected != null && expected.equals(row.getRevision()), "print.revisionConflict");
    }

    public static PrintOperation succeeded(
            String type, String targetType, String targetNo, FrozenJson result) {
        return new PrintOperation(
                no("PO"),
                type,
                PrintOperationStatus.SUCCEEDED,
                targetType,
                targetNo,
                result,
                null,
                Instant.now(),
                Instant.now());
    }
}
