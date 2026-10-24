// Recognize only the existing typed navigation helper imported from its real module.
// A route table, an arbitrary helper of the same name, or a dynamic argument is not an edge.
export function managementRouteRef(text, key) {
    if (!/import\s*\{[^}]*\bmanagementGo\b[^}]*\}\s*from\s*['"]@\/shared\/management['"]/.test(text)) return false;
    return new RegExp(`\\bmanagementGo\\s*\\(\\s*(['"])${key}\\1\\s*[,)]`).test(text);
}
