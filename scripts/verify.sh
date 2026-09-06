#!/usr/bin/env bash
#
# Local verification script for the Echo multiplatform repository.
# Runs the same checks CI runs (minus Xcode steps when not on macOS).
#
# Usage: ./scripts/verify.sh [--quick]
#
set -euo pipefail
cd "$(dirname "$0")/.."

QUICK=0
[[ "${1:-}" == "--quick" ]] && QUICK=1

echo "==> Static checks"

echo "  [1/6] No Android/JVM-only imports in commonMain"
# `androidx.compose.*` is Compose Multiplatform: the same package name is used
# on Android, desktop and iOS, so it is *not* a platform leak. Everything else
# under android/androidx plus java/javax/okhttp3 is JVM-only and must not
# appear in commonMain.
if grep -rn --include='*.kt' -E '^\s*import (android\.|androidx\.|java\.|javax\.|okhttp3\.)' \
    common/src/commonMain/kotlin composeApp/src/commonMain/kotlin \
    | grep -vE '^\S+:[0-9]+:\s*import androidx\.compose\.'; then
  echo "ERROR: platform imports found in commonMain"; exit 1
fi
echo "        OK"

echo "  [2/6] No production TODO/FIXME/NotImplementedError"
if grep -rn --include='*.kt' -E '(TODO\(|FIXME|NotImplementedError|error\("not implemented)' \
    common/src composeApp/src/commonMain composeApp/src/androidMain composeApp/src/iosMain \
    | grep -v 'common/src/commonMain/kotlin/dev/brahmkshatriya/echo/common/README.md'; then
  echo "ERROR: unfinished markers found (see above)"; exit 1
fi
echo "        OK"

echo "  [3/6] No secrets / signing material tracked by git"
if git ls-files | grep -Ei '\.(p12|mobileprovision|pem|key|jks|keystore)$|google-services\.json|credentials'; then
  echo "ERROR: potential secrets tracked by git"; exit 1
fi
echo "        OK"

echo "  [4/6] Xcode project structure"
test -f iosApp/iosApp.xcodeproj/project.pbxproj
test -f iosApp/iosApp/Info.plist
test -f iosApp/Configuration/Config.xcconfig
python3 - <<'EOF'
import re
content = open("iosApp/iosApp.xcodeproj/project.pbxproj").read()
ids = re.findall(r'\b([0-9A-F]{24})\b', content)
assert len(ids) > 20, "suspiciously few pbxproj ids"
assert content.count("{") == content.count("}"), "unbalanced braces"
print("        OK (pbxproj ids + braces valid)")
EOF

# CI invokes `xcodebuild -scheme iosApp`, which only works from a *shared*
# scheme: user schemes live in xcuserdata/ and are never committed.
SCHEME="iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme"
test -f "$SCHEME" || { echo "ERROR: no shared scheme at $SCHEME"; exit 1; }
python3 - <<'EOF'
import re
import xml.etree.ElementTree as ET

SCHEME = "iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme"
root = ET.parse(SCHEME).getroot()
for action in ("BuildAction", "TestAction", "LaunchAction", "ArchiveAction"):
    assert root.find(action) is not None, f"scheme is missing {action}"

# Every BlueprintIdentifier in the scheme must be a real target in the project.
pbxproj = open("iosApp/iosApp.xcodeproj/project.pbxproj").read()
known = set(re.findall(r'\b([0-9A-F]{24})\b', pbxproj))
refs = [r.get("BlueprintIdentifier") for r in root.iter("BuildableReference")]
assert refs, "scheme declares no BuildableReference"
for ref in refs:
    assert ref in known, f"scheme references unknown target {ref}"

names = {r.get("BlueprintName") for r in root.iter("BuildableReference")}
assert "iosApp" in names, "scheme does not build the iosApp target"
assert "iosAppTests" in names, "scheme has no test target: `xcodebuild test` would find nothing"
print("        OK (shared scheme valid: %s)" % ", ".join(sorted(names)))
EOF

if command -v plutil >/dev/null 2>&1; then
  plutil -lint iosApp/iosApp/Info.plist
fi

echo "  [5/6] GitHub workflow YAML parse"
python3 - <<'EOF'
try:
    import yaml
except ImportError:
    print("        (PyYAML not installed, skipping)")
    raise SystemExit(0)
import glob
for path in glob.glob(".github/workflows/*.yml"):
    with open(path) as fh:
        yaml.safe_load(fh)
    print(f"        OK {path}")
EOF

echo "  [6/6] iOS Info.plist: background audio + Compose high-refresh opt-in"
grep -q "UIBackgroundModes" iosApp/iosApp/Info.plist
grep -q "<string>audio</string>" iosApp/iosApp/Info.plist
# ComposeUIViewController throws IllegalStateException at startup without this.
grep -q "CADisableMinimumFrameDurationOnPhone" iosApp/iosApp/Info.plist
grep -A1 "CADisableMinimumFrameDurationOnPhone" iosApp/iosApp/Info.plist | grep -q "<true/>"
echo "        OK"

if command -v ./gradlew >/dev/null 2>&1; then
  echo "==> Gradle: shared modules (Android targets)"
  # :composeApp uses the AGP Kotlin-multiplatform library plugin: it has no
  # variant assemble tasks (no `assembleDebug`), only the lifecycle `assemble`.
  ./gradlew :common:assemble :composeApp:assemble --stacktrace

  echo "==> Gradle: shared unit tests"
  # `com.android.kotlin.multiplatform.library` has no debug/release variants, so
  # the classic `testDebugUnitTest` name only exists when Android host tests are
  # enabled. Discover whatever the plugin actually provides instead of
  # hard-coding a task name that may not exist.
  TEST_TASKS=$(./gradlew -q :composeApp:tasks --all 2>/dev/null \
    | grep -oE 'test(Debug|Release)UnitTest|testAndroidHostTest' | sort -u || true)
  if [ -n "$TEST_TASKS" ]; then
    echo "    test tasks: $(echo "$TEST_TASKS" | tr '\n' ' ')"
    ./gradlew $(echo "$TEST_TASKS" | sed 's|^|:composeApp:|' | tr '\n' ' ') --stacktrace
  else
    # Until Android host tests are enabled, src/commonTest is only executed by
    # the Kotlin/Native targets -- i.e. by `iosSimulatorArm64Test` (iOS CI, and
    # the macOS section below). Say so loudly rather than pretending we tested.
    echo "    WARN: :composeApp exposes no Android unit-test task."
    echo "          src/commonTest runs via :composeApp:iosSimulatorArm64Test (macOS only)."
  fi

  if [[ "$QUICK" == "0" ]]; then
    echo "==> Gradle: Android app regression build"
    ./gradlew :app:assembleDebug --stacktrace
  fi
else
  echo "==> Gradle wrapper not executable; run: chmod +x ./gradlew"
fi

if [[ "$(uname -s)" == "Darwin" ]]; then
  echo "==> Gradle: iOS framework + native tests"
  ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 \
            :composeApp:iosSimulatorArm64Test --stacktrace
  echo "==> Xcode: build the iOS app for the simulator"
  SIMULATOR=$(xcrun simctl list devices available | grep -oE 'iPhone [^(]+' | head -1 | sed 's/[[:space:]]*$//')
  xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -destination "platform=iOS Simulator,name=$SIMULATOR" \
    -derivedDataPath build/DerivedData \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
else
  echo "==> (macOS only steps skipped on $(uname -s))"
fi

echo "All verifications passed."
# CI retry marker
