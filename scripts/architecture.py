#!/usr/bin/env python3
"""Dependency fitness functions. Deliberately independent of Gradle/Xcode.

Checks declared Gradle edges and production imports; compilation, binary API
compatibility and runtime integration remain separate release gates.
"""
from pathlib import Path
import re

ALLOWED = {
    'common': set(),
    'shared': {'common'},
    'core': {'shared', 'common'},
    'domain': {'core', 'shared', 'common'},
    'data': {'domain', 'core', 'shared', 'common'},
    'extensions': {'data', 'domain', 'core', 'shared', 'common'},
    'player': {'extensions', 'data', 'domain', 'core', 'shared', 'common'},
    'composeApp': {'player', 'extensions', 'data', 'domain', 'core', 'shared', 'common'},
    'app-android': {'composeApp', 'player', 'extensions', 'data', 'domain', 'core', 'shared', 'common'},
    'wearApp': {'player', 'data', 'domain', 'core', 'shared', 'common'},
}
PLATFORM_IMPORT = re.compile(
    r'^(android\.|java\.|javax\.|okhttp3\.|platform\.|kotlinx\.cinterop\.|'
    r'androidx\.(media3|appcompat|fragment|preference|activity|work|palette|recyclerview|wear)\.)')
PACKAGE = re.compile(r'^\s*package\s+([\w.]+)', re.M)
IMPORT = re.compile(r'^\s*import\s+([\w.*]+)', re.M)
DECLARATION = re.compile(r'^\s*(?:(?:public|internal|private|open|sealed|data|enum|expect|actual|abstract|value|fun)\s+)*'
                         r'(?:class|interface|object|typealias)\s+(\w+)', re.M)


def without_comments(text):
    # Preserve string literals: Gradle project(":domain") edges live in them.
    return re.sub(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/',
                  lambda m: m[0] if m[0].startswith('"') else ' ', text)


def cycles(graph):
    errors, visiting, visited = [], [], set()

    def visit(node):
        if node in visiting:
            errors.append('dependency cycle: ' + ' -> '.join(visiting[visiting.index(node):] + [node]))
            return
        if node in visited:
            return
        visiting.append(node)
        for dependency in sorted(graph.get(node, set())):
            visit(dependency)
        visiting.pop()
        visited.add(node)
    for node in sorted(graph):
        visit(node)
    return errors


def validate_graph(graph, allowed=ALLOWED):
    errors = cycles(graph)
    for module, dependencies in graph.items():
        if module not in allowed:
            errors.append(f'unreviewed module: {module}')
        for dependency in dependencies:
            if dependency not in graph:
                errors.append(f'{module}: unknown dependency {dependency}')
            if dependency not in allowed.get(module, set()):
                errors.append(f'{module} -> {dependency}: forbidden layer dependency')
    return errors


def dependency_closure(graph, module):
    result, pending = set(), list(graph.get(module, set()))
    while pending:
        dependency = pending.pop()
        if dependency not in result:
            result.add(dependency)
            pending.extend(graph.get(dependency, set()))
    return result


def inspect(root):
    root = Path(root)
    settings = without_comments((root / 'settings.gradle.kts').read_text())
    modules = set(re.findall(r'include\(\s*":([\w-]+)"\s*\)', settings))
    errors, graph, sources, symbols = [], {}, [], {}
    for build in root.glob('*/build.gradle.kts'):
        if build.parent.name not in modules:
            errors.append(f'{build.parent.name}: Gradle module not included')
    for module in sorted(modules):
        build = root / module / 'build.gradle.kts'
        if not build.is_file():
            errors.append(f'{module}: missing build.gradle.kts')
            continue
        graph[module] = set(re.findall(r'project\(\s*":([\w-]+)"\s*\)', without_comments(build.read_text())))
        for path in sorted((root / module / 'src').rglob('*.kt')):
            source_set = path.relative_to(root / module / 'src').parts[0]
            if 'test' in source_set.lower():
                continue
            code = without_comments(path.read_text())
            package = PACKAGE.search(code)
            if not package:
                errors.append(f'{path.relative_to(root)}: missing package')
                continue
            package = package[1]
            sources.append((module, source_set, path, code))
            for name in DECLARATION.findall(code):
                symbols.setdefault(package + '.' + name, set()).add(module)
    errors += validate_graph(graph)
    for module, source_set, path, code in sources:
        visible = dependency_closure(graph, module) | {module}
        for imported in IMPORT.findall(code):
            if source_set == 'commonMain' and PLATFORM_IMPORT.match(imported):
                errors.append(f'{path.relative_to(root)}: platform import in commonMain: {imported}')
            name = imported
            while name and name not in symbols:
                name = name.rpartition('.')[0]
            owners = symbols.get(name, set())
            if owners and not owners & visible:
                errors.append(f'{path.relative_to(root)}: {imported} belongs to {sorted(owners)}, outside dependencies')
            if module in {'core', 'domain', 'shared', 'data', 'extensions', 'player'} and imported.startswith(('androidx.compose.', 'org.jetbrains.skia.')):
                errors.append(f'{path.relative_to(root)}: UI dependency outside presentation')
    return errors


if __name__ == '__main__':
    import sys
    problems = inspect(Path(__file__).resolve().parent.parent)
    for problem in problems:
        print(problem)
    print(f'Architecture: {len(problems)} violations')
    sys.exit(bool(problems))
