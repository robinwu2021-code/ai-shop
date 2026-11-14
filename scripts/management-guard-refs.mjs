import ts from 'typescript';

/** Only the imported management wrapper maps a literal first argument to ROUTES[page]. */
export function managementRouteRefs(text, aliases) {
  const imported = /import\s*\{([^}]+)\}\s*from\s*['"]@\/shared\/management['"]/g;
  const names = [];
  for (const match of text.matchAll(imported)) {
    for (const spec of match[1].split(',')) {
      const binding = spec.trim().match(/^managementGo(?:\s+as\s+(\w+))?$/);
      if (binding) names.push(binding[1] || 'managementGo');
    }
  }
  const routes = new Set();
  for (const name of names) {
    for (const match of text.matchAll(new RegExp(`\\b${name}\\s*\\(\\s*(['"])(\\w+)\\1\\s*(?=[,)])`, 'g'))) {
      for (const [page, alias] of aliases) if (alias === match[2]) routes.add(page);
    }
  }
  return routes;
}

/** Remove permission DATA tokens, preserving separate t('same.code') occurrences. */
export function stripManagementPermissionData(script, onPermission = () => {}) {
  const file = ts.createSourceFile('page.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const ranges = [];
  const wrappers = new Map();
  const walk = (node, fn) => { fn(node); ts.forEachChild(node, child => walk(child, fn)); };
  const grant = node => ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression)
    && node.expression.expression.getText(file) === 'api' && node.expression.name.text === 'mManagementGrant';
  const permissionValue = call => {
    const body = call.arguments[1];
    if (!body || !ts.isObjectLiteralExpression(body)) return;
    const prop = body.properties.find(p => p.name?.getText(file) === 'permissionCode');
    return prop && (ts.isPropertyAssignment(prop) ? prop.initializer : ts.isShorthandPropertyAssignment(prop) ? prop.name : undefined);
  };
  walk(file, node => {
    if (!ts.isVariableDeclaration(node) || !node.initializer || !ts.isArrowFunction(node.initializer)) return;
    const arrow = node.initializer;
    if (!grant(arrow.body)) return;
    const value = permissionValue(arrow.body);
    const index = arrow.parameters.findIndex(p => p.name.getText(file) === value?.getText(file));
    if (index >= 0) wrappers.set(node.name.getText(file), index);
  });
  const mark = (node, label = true) => {
    if (node && ts.isStringLiteralLike(node)) {
      ranges.push([node.getStart(file), node.end]);
      if (label) onPermission(`management.permission.${node.text.replaceAll(".", "_")}`);
    }
    if (node && ts.isIdentifier(node)) {
      // A literal enum in `for (const permission of [...])` is data only when
      // this exact loop binding feeds the grant's permission argument.
      for (let parent = node.parent; parent; parent = parent.parent) {
        if (!ts.isForOfStatement(parent) || !ts.isVariableDeclarationList(parent.initializer)) continue;
        if (!parent.initializer.declarations.some(d => d.name.getText(file) === node.text)) continue;
        if (ts.isArrayLiteralExpression(parent.expression)) for (const item of parent.expression.elements) {
          if (ts.isStringLiteralLike(item)) {
            ranges.push([item.getStart(file), item.end]);
            if (label) onPermission(`management.permission.${item.text.replaceAll(".", "_")}`);
          }
        }
        break;
      }
    }
  };
  walk(file, node => {
    if (!ts.isCallExpression(node)) return;
    if (grant(node)) mark(permissionValue(node));
    const index = wrappers.get(node.expression.getText(file));
    if (index !== undefined) mark(node.arguments[index]);
    if (ts.isPropertyAccessExpression(node.expression) && node.expression.name.text === 'includes'
      && ts.isPropertyAccessExpression(node.expression.expression) && node.expression.expression.name.text === 'actions') mark(node.arguments[0], false);
  });
  let result = script;
  for (const [start, end] of ranges.sort((a,b) => b[0]-a[0])) result = result.slice(0,start) + ' '.repeat(end-start) + result.slice(end);
  return result;
}

export function stripTemplateActionData(template) {
  return template.replace(/\b[\w.]+\.actions\.includes\(\s*(['"])[\w.]+\1\s*\)/g, 'true');
}
