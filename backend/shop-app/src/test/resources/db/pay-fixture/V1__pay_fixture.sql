-- PayMigrationTest 专用：证明 db/pay 走的是独立历史表。不是真迁移。
CREATE TABLE pay_fixture_probe (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    note VARCHAR(32) NOT NULL
);
