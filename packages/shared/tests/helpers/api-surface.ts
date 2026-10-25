import { existsSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import ts from "typescript";

/** Read actual declarations, including inherited contracts and imported object spreads. */
export function apiSurface(file: string, name: string, visiting = new Set<string>()): Set<string> {
  const key = `${file}:${name}`;
  if (visiting.has(key)) throw new Error(`Cyclic API declaration: ${key}`);
  const next = new Set(visiting).add(key);
  const src = ts.createSourceFile(file, readFileSync(file, "utf8"), ts.ScriptTarget.Latest, true);
  const moduleFile = (specifier: string) => {
    if (!specifier.startsWith(".")) throw new Error(`API declaration requires a local import: ${specifier}`);
    const base = resolve(dirname(file), specifier);
    const found = [base + ".ts", resolve(base, "index.ts")].find(existsSync);
    if (!found) throw new Error(`Missing API declaration module: ${base}`);
    return found;
  };
  const merge = (target: Set<string>, values: Set<string>) => { for (const value of values) target.add(value); };
  const memberName = (node: ts.PropertyName) => {
    if (ts.isIdentifier(node) || ts.isStringLiteral(node)) return node.text;
    throw new Error(`Unsupported API member in ${file}: ${node.getText(src)}`);
  };
  const objectKeys = (node: ts.Expression): Set<string> => {
    if (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isSatisfiesExpression(node)) {
      return objectKeys(node.expression);
    }
    if (!ts.isObjectLiteralExpression(node)) throw new Error(`Expected an API object: ${key}`);
    const result = new Set<string>();
    for (const property of node.properties) {
      if (ts.isSpreadAssignment(property)) {
        if (!ts.isIdentifier(property.expression)) throw new Error(`Unsupported API spread: ${key}`);
        merge(result, apiSurface(file, property.expression.text, next));
      } else result.add(memberName(property.name));
    }
    return result;
  };
  for (const statement of src.statements) {
    if (ts.isImportDeclaration(statement) && ts.isStringLiteral(statement.moduleSpecifier)) {
      const bindings = statement.importClause?.namedBindings;
      if (bindings && ts.isNamedImports(bindings)) for (const binding of bindings.elements) {
        if (binding.name.text === name) {
          return apiSurface(moduleFile(statement.moduleSpecifier.text), binding.propertyName?.text ?? name, next);
        }
      }
    }
    if (ts.isExportDeclaration(statement) && statement.moduleSpecifier
        && ts.isStringLiteral(statement.moduleSpecifier) && statement.exportClause
        && ts.isNamedExports(statement.exportClause)) {
      for (const binding of statement.exportClause.elements) if (binding.name.text === name) {
        return apiSurface(moduleFile(statement.moduleSpecifier.text), binding.propertyName?.text ?? name, next);
      }
    }
    if (ts.isInterfaceDeclaration(statement) && statement.name.text === name) {
      const result = new Set<string>();
      for (const clause of statement.heritageClauses ?? []) for (const type of clause.types) {
        if (!ts.isIdentifier(type.expression)) throw new Error(`Unsupported API inheritance: ${key}`);
        merge(result, apiSurface(file, type.expression.text, next));
      }
      for (const member of statement.members) if (member.name) result.add(memberName(member.name));
      return result;
    }
    if (ts.isVariableStatement(statement)) for (const declaration of statement.declarationList.declarations) {
      if (ts.isIdentifier(declaration.name) && declaration.name.text === name && declaration.initializer) {
        return objectKeys(declaration.initializer);
      }
    }
  }
  throw new Error(`Missing API declaration: ${key}`);
}
