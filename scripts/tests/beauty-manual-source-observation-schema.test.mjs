import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {renderManualSourceSchema} from '../gen-beauty-manual-source-observation-schema.mjs';
const design = JSON.parse(fs.readFileSync(new URL(
  '../../docs/technical/design/schema/beauty-manual-source-observation-schema.json', import.meta.url)));
test('V9 deterministically renders only the reviewed local source changes and trigger', () => {
  const rendered = renderManualSourceSchema(design);
  assert.equal(rendered, renderManualSourceSchema(structuredClone(design)));
  assert.equal([...rendered.matchAll(/ALTER TABLE/g)].length, 3);
  assert.equal([...rendered.matchAll(/CREATE TRIGGER/g)].length, 1);
  assert(!/UPDATE bty_|INSERT INTO|DELETE FROM/.test(rendered));
});
test('a foreign table, version or executable mutation cannot enter the bounded renderer', () => {
  for (const mutate of [d => { d.version = 8; }, d => { d.alterations[0].table = 'ord_order'; },
    d => { d.alterations[0].clauses.push('ADD COLUMN `x` INT; DELETE FROM bty_work_order'); },
    d => { d.trigger.body.splice(1, 0, 'UPDATE bty_work_order SET version=0;'); }]) {
    const changed = structuredClone(design); mutate(changed);
    assert.throws(() => renderManualSourceSchema(changed));
  }
});

test('bounded grammar rejects invalid type, unknown check column and foreign trigger SELECT', () => {
  for (const mutate of [
    d => { d.alterations[0].clauses[0] = "ADD COLUMN `admission_kind` EVILTYPE NULL"; },
    d => { d.alterations[0].clauses.push("ADD CONSTRAINT `ck_bty_unknown` CHECK (`unknown_owner` IS NULL)"); },
    d => { d.trigger.body.splice(1, 0, "IF EXISTS (SELECT 1 FROM ord_order) THEN SIGNAL SQLSTATE '45000'; END IF;"); }
  ]) {
    const changed = structuredClone(design); mutate(changed);
    assert.throws(() => renderManualSourceSchema(changed));
  }
});

test('trigger grammar rejects comma FROM references even inside an otherwise valid EXISTS', () => {
  for (const mutate of [
    d => d.trigger.body.splice(1, 0,
      'SET NEW.manual_epoch = (SELECT COUNT(*) FROM bty_trade_binding b, ord_order o);'),
    d => { d.trigger.body = d.trigger.body.map(line => line.replace(
      'FROM bty_trade_binding b WHERE', 'FROM bty_trade_binding b, ord_order o WHERE')); }
  ]) {
    const changed = structuredClone(design); mutate(changed);
    assert.throws(() => renderManualSourceSchema(changed));
  }
});

test('trigger grammar rejects arbitrary SET and executable statements', () => {
  for (const statement of ['SET NEW.manual_epoch=999;', 'DO SLEEP(1);']) {
    const changed = structuredClone(design);
    changed.trigger.body.splice(1, 0, statement);
    assert.throws(() => renderManualSourceSchema(changed));
  }
});

test('reviewed V9 output remains byte-identical to the frozen migration', () => {
  const frozen = fs.readFileSync(new URL('../../backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/'
    + 'db/industry/beauty/V9__beauty_manual_source_observation.sql', import.meta.url), 'utf8');
  assert.equal(renderManualSourceSchema(design), frozen);
});
