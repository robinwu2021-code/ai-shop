import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renderType, validateSpec } from '../gen-entitlement-backend-contracts.mjs';

test('nullable money, literal model selection and arbitrary JSON retain their distinct contracts', () => {
    assert.equal(renderType({ anyOf: [{ type: 'integer', format: 'int64' }, { type: 'null' }] }), 'number | null');
    assert.equal(renderType({ type: 'string', const: 'unified' }), '"unified"');
    assert.equal(renderType({}), 'unknown');
    assert.equal(renderType({ type: 'object', properties: { optional: {}, required: { type: 'boolean' } }, required: ['required'] }),
        '{ "optional"?: unknown; "required": boolean; }');
    assert.equal(renderType({ type: 'array', items: { $ref: '#/components/schemas/ActualDto' } }), 'Array<ActualDto>');
});
test('dangling references and path-parameter drift are hard errors', () => {
    assert.throws(() => validateSpec({ openapi: '3.1.0', components: { schemas: {} },
        paths: { '/x': { get: { $ref: '#/components/schemas/Missing' } } } }), /Missing DTO/);
    assert.throws(() => validateSpec({ openapi: '3.1.0', components: { schemas: {} },
        paths: { '/x/{instanceNo}': { get: { operationId: 'x', parameters: [{ in: 'path', name: 'no', required: true }] } } } }),
    /Path parameters differ/);
});
