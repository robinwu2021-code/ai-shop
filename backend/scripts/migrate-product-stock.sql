-- Run once SKU metadata and prices are loaded, with sales paused through reconciliation.
-- product_stock_snapshot contains frozen copies of the old prd_sku and prd_store_stock.
-- Connect the mysql batch client to the destination database; stop on the first SQL error.
-- A failed assertion rolls back when that connection closes. Never use --force.
SET @home_market = COALESCE(@home_market, 'TW');

CREATE TEMPORARY TABLE stock_import_guard (
    problem_count BIGINT NOT NULL CHECK (problem_count = 0)
);
CREATE TEMPORARY TABLE stock_import (
    tenant_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    entity_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    store_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    sku_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    quantity INT NOT NULL,
    held INT NOT NULL,
    PRIMARY KEY (store_no, sku_no),
    CHECK (quantity >= held AND held >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;

-- Old market rows represent one physical balance. Divergent copies need reconciliation.
INSERT INTO stock_import_guard
SELECT COUNT(*) FROM (
    SELECT sku_no FROM product_stock_snapshot.prd_sku WHERE deleted=0
    GROUP BY sku_no HAVING COUNT(DISTINCT entity_no) <> 1 OR COUNT(DISTINCT tenant_no) <> 1
        OR MIN(stock) <> MAX(stock) OR MIN(locked_stock) <> MAX(locked_stock)
) AS divergent;
INSERT INTO stock_import_guard
SELECT COUNT(*) FROM product_stock_snapshot.prd_store_stock WHERE deleted=0 AND store_no='';

INSERT INTO stock_import (tenant_no,entity_no,store_no,sku_no,quantity,held)
SELECT tenant_no,entity_no,'',sku_no,MIN(stock),MIN(locked_stock)
FROM product_stock_snapshot.prd_sku WHERE deleted=0 GROUP BY tenant_no,entity_no,sku_no;
INSERT INTO stock_import (tenant_no,entity_no,store_no,sku_no,quantity,held)
SELECT tenant_no,entity_no,store_no,sku_no,stock,locked_stock
FROM product_stock_snapshot.prd_store_stock WHERE deleted=0;

-- Reject missing or differently owned identities and conflicting destination quantities.
INSERT INTO stock_import_guard
SELECT COUNT(*) FROM stock_import src WHERE NOT EXISTS (
    SELECT 1 FROM prd_sku s WHERE s.sku_no=src.sku_no AND s.tenant_no=src.tenant_no
        AND s.entity_no=src.entity_no AND s.deleted=0
);
INSERT INTO stock_import_guard
SELECT COUNT(*) FROM stock_import src JOIN prd_store_sku dest
    ON dest.store_no=src.store_no AND dest.sku_no=src.sku_no
WHERE dest.deleted<>0 OR dest.entity_no<>src.entity_no OR dest.entity_no IS NULL
    OR dest.tenant_no<>src.tenant_no OR (dest.sellable_qty IS NOT NULL AND
        (dest.market<>@home_market OR dest.sellable_qty<>src.quantity OR dest.locked_qty<>src.held))
    OR (dest.sellable_qty IS NULL AND dest.locked_qty<>0);

-- Existing price-only rows keep their price, crossed-out price, and sale flag.
UPDATE prd_store_sku dest JOIN stock_import src
    ON dest.store_no=src.store_no AND dest.sku_no=src.sku_no AND dest.market=@home_market
SET dest.sellable_qty=src.quantity,dest.locked_qty=src.held,dest.version=dest.version+1
WHERE dest.deleted=0 AND dest.sellable_qty IS NULL;
INSERT INTO prd_store_sku (tenant_no,entity_no,store_no,sku_no,market,sellable_qty,locked_qty)
SELECT src.tenant_no,src.entity_no,src.store_no,src.sku_no,@home_market,src.quantity,src.held
FROM stock_import src WHERE NOT EXISTS (
    SELECT 1 FROM prd_store_sku dest WHERE dest.store_no=src.store_no
        AND dest.sku_no=src.sku_no AND dest.market=@home_market
);

INSERT INTO stock_import_guard
SELECT COUNT(*) FROM stock_import src WHERE NOT EXISTS (
    SELECT 1 FROM prd_store_sku dest WHERE dest.store_no=src.store_no AND dest.sku_no=src.sku_no
        AND dest.market=@home_market AND dest.tenant_no=src.tenant_no AND dest.entity_no=src.entity_no
        AND dest.deleted=0 AND dest.sellable_qty=src.quantity AND dest.locked_qty=src.held
);
SELECT IF(store_no='','ENTITY','STORE') AS pool, COUNT(*) AS balances,
       SUM(quantity) AS total_units, SUM(held) AS held_units FROM stock_import GROUP BY pool;
COMMIT;
