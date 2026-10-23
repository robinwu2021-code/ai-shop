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
    const contracts = data.contracts ?? ['docs/api/entitlement-backend.openapi.json'];
    if (!Array.isArray(contracts) || !contracts.length || contracts.length > 16
            || new Set(contracts).size !== contracts.length
            || contracts.some(file => typeof file !== 'string'
                || !/^docs\/api\/[a-z0-9-]+\.openapi\.json$/.test(file)
                || !existsSync(join(root, file)))) {
        throw new Error('Staged delivery requires existing, unique API contracts under docs/api');
    }
    const published = new Set();
    for (const file of contracts) {
        const spec = JSON.parse(readFileSync(join(root, file), 'utf8'));
        if (!spec.openapi?.startsWith('3.') || !spec.paths || typeof spec.paths !== 'object') {
            throw new Error(`Invalid staged OpenAPI contract: ${file}`);
        }
        for (const [path, methods] of Object.entries(spec.paths)) {
            for (const method of Object.keys(methods)) {
                if (['get', 'post', 'put', 'patch', 'delete'].includes(method)) {
                    published.add(keyOf({method: method.toUpperCase(), path}));
                }
            }
        }
    }
    return validateDeferredDelivery(data, {app, prefix, published,
        handlers: new Set(handlers.map(keyOf)), missing: new Set(missing.map(keyOf))});
}
