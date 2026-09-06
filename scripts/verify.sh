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
if grep -rn --include='*.kt' -E '^\s*import (android\.|androidx\.|java\.|javax\.|okhttp3\.)' \
    common/src/commonMain/kotlin composeApp/src/commonMain/kotlin; then
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

echo "  [6/6] Background audio configured on iOS"
grep -q "UIBackgroundModes" iosApp/iosApp/Info.plist
grep -q "<string>audio</string>" iosApp/iosApp/Info.plist
echo "        OK"

if command -v ./gradlew >/dev/null 2>&1; then
  echo "==> Gradle: shared unit tests + Android compilation"
  ./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug --stacktrace
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
