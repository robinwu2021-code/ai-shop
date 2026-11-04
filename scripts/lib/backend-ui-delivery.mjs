import {existsSync, readFileSync} from 'node:fs';
import {join} from 'node:path';

const norm = path => path.replace(/\$\{[^}]*\}/g, '*').replace(/\{[^}]*\}/g, '*').replace(/\/+$/, '');
const keyOf = row => `${row.method} ${norm(row.path)}`;

export function validateDeferredDelivery(data, {app, prefix, published, handlers, missing}) {
    if (data.version !== 1 || data.stage !== 'JAVA_BACKEND_UI_DEFERRED'
            || typeof data.reason !== 'string' || !data.reason.trim() || !Array.isArray(data.entries)) {
        throw new Error('Invalid backend/UI delivery stage or missing rationale');
    }
    const result = new Set();
    for (const row of data.entries) {
        if (row.app !== app || typeof row.path !== 'string' || !row.path.startsWith(prefix + '/')) {
            throw new Error('Staged API belongs to the wrong application');
        }
        const key = keyOf(row);
        if (result.has(key) || !published.has(key) || !handlers.has(key)) {
            throw new Error(`Duplicate staged API or missing published contract/handler: ${key}`);
        }
        if (!missing.has(key)) throw new Error(`Staged API connected or deleted; remove its entry: ${key}`);
        result.add(key);
    }
    return result;
}

export function readDeferredDelivery(root, file, {app, prefix, handlers, missing}) {
    if (!existsSync(join(root, file))) return new Set();
    const data = JSON.parse(readFileSync(join(root, file), 'utf8'));
    if (typeof data.plan !== 'string' || !data.plan.startsWith('docs/technical/')
            || data.plan.split('/').includes('..') || !existsSync(join(root, data.plan))) {
        throw new Error('Staged delivery must name an existing frontend task plan');
    }
    // 每份清单可以用 contract 指明它的条目必须出现在哪份 docs/api 下的 OpenAPI 里（按域分清单）；
    // 不写则沿用最早那份权益契约。
    const contract = data.contract ?? 'docs/api/entitlement-backend.openapi.json';
    if (typeof contract !== 'string' || !contract.startsWith('docs/api/') || contract.split('/').includes('..')
            || !existsSync(join(root, contract))) {
        throw new Error('Staged delivery must name an existing docs/api OpenAPI contract');
    }
    const spec = JSON.parse(readFileSync(join(root, contract), 'utf8'));
    const published = new Set(Object.entries(spec.paths).flatMap(([path, methods]) => Object.keys(methods)
        .filter(method => ['get', 'post', 'put', 'patch', 'delete'].includes(method))
        .map(method => keyOf({method: method.toUpperCase(), path}))));
    return validateDeferredDelivery(data, {app, prefix, published,
        handlers: new Set(handlers.map(keyOf)), missing: new Set(missing.map(keyOf))});
}
