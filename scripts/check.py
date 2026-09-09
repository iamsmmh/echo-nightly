#!/usr/bin/env python3
"""Static consistency checks for the Echo repository.

This sandbox cannot run Gradle (no JDK/Android SDK, blocked egress), so this
script provides fast local feedback on the classes of errors that CI would
otherwise catch: unbalanced code, wrong modules/paths, YAML problems and
dangling imports between the KMP modules.
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

ERRORS = []


def err(msg):
    ERRORS.append(msg)


# ----------------------------------------------------------------------------
# 1. Kotlin file sanity: balanced braces/parens outside strings/comments,
#    package matches directory for files we manage.
# ----------------------------------------------------------------------------
def strip_code(text: str) -> str:
    """Remove strings/comments from Kotlin code (handles nested block comments)."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            i = n if j == -1 else j
        elif c == "/" and nxt == "*":
            depth = 1
            i += 2
            while i < n and depth:
                if text.startswith("/*", i):
                    depth += 1; i += 2
                elif text.startswith("*/", i):
                    depth -= 1; i += 2
                else:
                    i += 1
        elif text.startswith('\"\"\"', i):
            j = text.find('\"\"\"', i + 3)
            i = n if j == -1 else j + 3
        elif c == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                elif text[i] == '"':
                    i += 1
                    break
                else:
                    i += 1
        elif c == "'":
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                elif text[i] == "'":
                    i += 1
                    break
                else:
                    i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_balance(path, text):
    code = strip_code(text)
    for opener, closer in [("{", "}"), ("(", ")"), ("[", "]")]:
        o, c = code.count(opener), code.count(closer)
        if o != c:
            err(f"{path}: unbalanced '{opener}{closer}' {o} vs {c}")

KT_ROOTS = ["app-android", "app", "common", "composeApp", "shared", "core",
            "domain", "data", "player", "extensions"]

for root in KT_ROOTS:
    if not os.path.isdir(root):
        continue
    for path in glob.glob(f"{root}/src/**/*.kt", recursive=True):
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        check_balance(path, text)
        m = re.search(r'^\s*package\s+([\w.]+)', text, re.M)
        if not m:
            err(f"{path}: missing package declaration")
            continue
        pkg = m.group(1)
        norm = path.replace("\\", "/")
        # path after .../kotlin/
        idx = norm.find("/kotlin/")
        if idx != -1:
            expect_dir = os.path.dirname(norm[idx + len("/kotlin/"):]).replace("/", ".")
            if expect_dir and expect_dir != pkg:
                err(f"{path}: package '{pkg}' != directory '{expect_dir}'")
        if "TODO(" in text or "NotImplementedError" in text:
            if "/test/" not in path and "Test" not in os.path.basename(path):
                err(f"{path}: production TODO(/NotImplementedError")
        if "/commonMain/" in path.replace("\\", "/"):
            code_only = strip_code(text)
            if "System.currentTimeMillis" in code_only or "java.lang.System" in code_only:
                err(f"{path}: JVM wall-clock API is not available in commonMain")

# ----------------------------------------------------------------------------
# 2. Gradle settings <-> module dirs
# ----------------------------------------------------------------------------
settings = open("settings.gradle.kts").read()
includes = set(re.findall(r'include\("(:[^"]+)"\)', settings))
for mod in includes:
    d = mod.strip(":").replace(":", "/")
    if not os.path.isdir(d):
        err(f"settings.gradle.kts includes :{d.split('/')[0]}... but {d}/ missing")
    elif not os.path.isfile(f"{d}/build.gradle.kts"):
        # app-ios is a container for the Xcode project, not a gradle module
        if not (d == "app-ios" or os.path.isdir(f"{d}/build")):
            err(f"module {d} has no build.gradle.kts")

# every dir with build.gradle.kts should be included
for d in glob.glob("*/build.gradle.kts"):
    mod = os.path.dirname(d)
    if f":{mod}" not in includes:
        err(f"{mod}/build.gradle.kts exists but :{mod} not in settings.gradle.kts")

# ----------------------------------------------------------------------------
# 3–4. One source of truth for the module graph and platform/UI boundaries.
# ----------------------------------------------------------------------------
from architecture import inspect
ERRORS.extend(inspect(ROOT))

# ----------------------------------------------------------------------------
# 5. expect/actual pairs consistent per module
# ----------------------------------------------------------------------------
for mod in ["shared", "core", "domain", "data", "player", "extensions", "composeApp", "common"]:
    expects = {}
    for path in glob.glob(f"{mod}/src/commonMain/kotlin/**/*.kt", recursive=True):
        text = open(path, encoding="utf-8").read()
        for m in re.finditer(r'expect\s+(?:fun|class|val|object|interface)\s+(\w+)', text):
            expects[m.group(1)] = path
    actuals = {}
    for ss in ["androidMain", "iosMain", "jvmMain"]:
        for path in glob.glob(f"{mod}/src/{ss}/kotlin/**/*.kt", recursive=True):
            text = open(path, encoding="utf-8").read()
            for m in re.finditer(r'actual\s+(?:fun|class|val|object|typealias)\s+(\w+)', text):
                actuals.setdefault(m.group(1), set()).add(ss)
    if expects:
        has_jvm = os.path.isdir(f"{mod}/src/jvmMain")
        has_android = os.path.isdir(f"{mod}/src/androidMain")
        has_ios = os.path.isdir(f"{mod}/src/iosMain")
        for name in expects:
            a = actuals.get(name, set())
            if has_android and "androidMain" not in a:
                err(f"{mod}: expect {name} lacks actual in androidMain")
            if has_ios and "iosMain" not in a:
                err(f"{mod}: expect {name} lacks actual in iosMain")
            if has_jvm and "jvmMain" not in a:
                err(f"{mod}: expect {name} lacks actual in jvmMain")

# ----------------------------------------------------------------------------
# 6. YAML workflows
# ----------------------------------------------------------------------------
try:
    import yaml
    for path in glob.glob(".github/workflows/*.yml"):
        try:
            with open(path) as fh:
                yaml.safe_load(fh)
        except Exception as e:
            err(f"{path}: YAML error: {e}")
except ImportError:
    print("note: PyYAML not installed, skipping YAML check")

# ----------------------------------------------------------------------------
# 7. pbxproj sanity
# ----------------------------------------------------------------------------
for pbx in glob.glob("app-ios/iosApp/iosApp.xcodeproj/project.pbxproj"):
    text = open(pbx).read()
    if text.count("{") != text.count("}"):
        err(f"{pbx}: unbalanced braces")
    if text.count("(") != text.count(")"):
        err(f"{pbx}: unbalanced parentheses")

# ----------------------------------------------------------------------------
# 8. Workflows referencing module paths must match the repo layout
# ----------------------------------------------------------------------------
if os.path.isdir("app-android") and os.path.isdir("app"):
    err("both app/ and app-android/ exist; module move incomplete")
if os.path.isdir("app") and re.search(r'include\(":app-android"\)', settings):
    err("settings includes :app-android but app/ still present")

# ----------------------------------------------------------------------------
print("checks done")
if ERRORS:
    print(f"\n{len(ERRORS)} PROBLEM(S):")
    for e in ERRORS:
        print(" -", e)
    sys.exit(1)
print("ALL STATIC CHECKS PASSED")
