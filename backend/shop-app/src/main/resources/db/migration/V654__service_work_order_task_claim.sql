-- Current original intent for one work-order task. Financial facts remain in the original service order.
CREATE TABLE ord_ent_service_task_claim (
    tenant_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    entity_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    work_order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    task_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    service_order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (tenant_no, entity_no, work_order_no, task_no),
    CONSTRAINT uk_ent_service_task_order UNIQUE (tenant_no, service_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
