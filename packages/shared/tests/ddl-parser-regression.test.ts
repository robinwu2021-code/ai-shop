import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
// @ts-expect-error -- the shared migration parser is plain JavaScript
import { readSchema } from "../../../scripts/lib/ddl.mjs";

const fixtures: string[] = [];
function parse(sql: string) {
  const root = mkdtempSync(join(tmpdir(), "shop-ddl-"));
  fixtures.push(root);
  mkdirSync(join(root, "migrations"));
  writeFileSync(join(root, "migrations/V1__fixture.sql"), sql);
  return readSchema(root, ["migrations"]);
}
afterEach(() => fixtures.splice(0).forEach(root => rmSync(root, { recursive: true, force: true })));

describe("DDL parser handles actual migration syntax", () => {
  it("keeps multiline column comments and ignores CHECK continuation expressions", () => {
    const schema = parse(`CREATE TABLE demo (
      id BIGINT NOT NULL,
      status VARCHAR(20) NOT NULL
        COMMENT 'ACTIVE / ARCHIVED',
      -- A comment between complete column declarations must not hide the next column.
      name VARCHAR(64),
      CHECK (CHAR_LENGTH(name)=64
        AND CHAR_LENGTH(status)>0)
);`);
    expect(schema.get("demo").cols.map((c: { name: string }) => c.name)).toEqual(["id", "status", "name"]);
    expect(schema.get("demo").cols.find((c: { name: string }) => c.name === "status").comment)
      .toBe("ACTIVE / ARCHIVED");
  });

  it("replays bare and explicit ADD without treating constraints or indexes as columns", () => {
    const schema = parse(`CREATE TABLE demo (
      id BIGINT
);
    ALTER TABLE demo MODIFY id BIGINT NULL,
      ADD customer_no VARCHAR(64), ADD COLUMN IF NOT EXISTS issuer_no VARCHAR(64),
      ADD IF NOT EXISTS revision BIGINT,
      ADD CONSTRAINT uk_customer UNIQUE(customer_no), ADD INDEX ix_issuer(issuer_no);`);
    expect(schema.get("demo").cols.map((c: { name: string }) => c.name))
      .toEqual(["id", "customer_no", "issuer_no", "revision"]);
  });

  it("preserves comments when a bare MODIFY changes only the column type", () => {
    const schema = parse(`CREATE TABLE demo (
      status VARCHAR(16) NOT NULL COMMENT 'ACTIVE / ARCHIVED'
);
    ALTER TABLE demo MODIFY status VARCHAR(32) NULL;`);
    expect(schema.get("demo").cols[0]).toMatchObject({ name: "status", type: "varchar", nullable: true,
      comment: "ACTIVE / ARCHIVED" });
  });
});
