-- 接口资源：权限规则以库里为准（TDD-管理范围权限搬到网关-权限码方案「规则在库里」）。
-- access / rule_text 是启动时从入口注解抄进来的；下面两列是在库里做的调整，有值时网关按它判，扫描不会覆盖它。
-- 业务代码不为网关改写法：入口上写的是「只问是不是商家」的，要它带权限码，就在这里配。
ALTER TABLE sys_api_resource
    ADD COLUMN adjusted_access VARCHAR(16)   NULL COMMENT '调整后的放行方式（PUBLIC / LOGIN / POINT / DEFER）；空 = 用 access',
    ADD COLUMN adjusted_rule   VARCHAR(1000) NULL COMMENT '调整后的权限式，写法同 rule_text，另可写带范围的码「码 @类型(参数)」「码 @类型(任一)」；adjusted_access=POINT 时必填',
    ADD COLUMN adjusted_by     VARCHAR(64)   NULL COMMENT '做这次调整的人',
    ADD COLUMN adjusted_at     DATETIME      NULL COMMENT '做这次调整的时间';
