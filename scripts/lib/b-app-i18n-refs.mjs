import ts from 'typescript';

/** 完整静态 key 与动态表达式分开记录；不执行被扫描的代码。 */
export function translationCalls(code) {
  const ast = ts.createSourceFile('copy.ts', code, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const calls = [];
  function visit(node) {
    if (ts.isCallExpression(node) && ['t', '$t', 'tt', 'i18n.global.t'].includes(node.expression.getText(ast))) {
      const arg = node.arguments[0];
      calls.push({
        key: arg && (ts.isStringLiteral(arg) || ts.isNoSubstitutionTemplateLiteral(arg)) ? arg.text : null,
        expression: arg?.getText(ast) ?? '',
        line: ast.getLineAndCharacterOfPosition(node.getStart(ast)).line,
      });
    }
    ts.forEachChild(node, visit);
  }
  visit(ast);
  return calls;
}
