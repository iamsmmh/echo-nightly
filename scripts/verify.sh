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

echo "  [1/7] No Android/JVM-only imports in any commonMain source set"
# androidx.compose / lifecycle / navigation / datastore are multiplatform-safe;
# everything below is Android-or-JVM only.
if grep -rn --include='*.kt' -E '^\s*import (android\.|java\.|javax\.|okhttp3\.|androidx\.(media3|appcompat|fragment|preference|room|activity|work|palette|recyclerview|swiperefresh|splashscreen|paging\.android))' \
    common/src/commonMain/kotlin shared/src/commonMain/kotlin core/src/commonMain/kotlin \
    domain/src/commonMain/kotlin data/src/commonMain/kotlin extensions/src/commonMain/kotlin \
    player/src/commonMain/kotlin composeApp/src/commonMain/kotlin; then
  echo "ERROR: platform imports found in commonMain"; exit 1
fi
echo "        OK"

echo "  [2/7] No production TODO/FIXME/NotImplementedError"
if grep -rn --include='*.kt' -E '(TODO\(|FIXME|NotImplementedError|error\("not implemented)' \
    common/src shared/src core/src domain/src data/src extensions/src player/src \
    composeApp/src app-android/src wearApp/src 2>/dev/null | grep -v '/build/'; then
  echo "ERROR: unfinished markers found (see above)"; exit 1
fi
echo "        OK"

echo "  [3/7] No secrets / signing material tracked by git"
if git ls-files | grep -Ei '\.(p12|mobileprovision|pem|key|jks|keystore)$|google-services\.json|credentials'; then
  echo "ERROR: potential secrets tracked by git"; exit 1
fi
echo "        OK"

echo "  [4/7] Extension API (common/) stability guard"
# The published `dev.brahmkshatriya.echo:common` artifact is consumed by
# third-party extension APKs; its public signatures must never change in a
# breaking way on this branch.
BASE_REF="${BASE_REF:-origin/main}"
if git rev-parse --verify -q "$BASE_REF" >/dev/null; then
  CHANGED_API=$(git diff --name-only "$BASE_REF"...HEAD -- common/ || true)
  if [[ -n "${CHANGED_API:-}" && "${ALLOW_COMMON_CHANGES:-0}" != "1" ]]; then
    echo "ERROR: common/ (public extension API) changed in this branch:"
    echo "$CHANGED_API"
    echo "Revert or set ALLOW_COMMON_CHANGES=1 after careful review."
    exit 1
  fi
  echo "        OK (no changes under common/)"
else
  echo "        (base ref $BASE_REF unavailable; skipping)"
fi

echo "  [5/7] Xcode project structure"
test -f app-ios/iosApp/iosApp.xcodeproj/project.pbxproj
test -f app-ios/iosApp/iosApp/Info.plist
test -f app-ios/iosApp/Configuration/Config.xcconfig
python3 - <<'EOF'
import re
content = open("app-ios/iosApp/iosApp.xcodeproj/project.pbxproj").read()
ids = re.findall(r'\b([0-9A-F]{24})\b', content)
assert len(ids) > 20, "suspiciously few pbxproj ids"
assert content.count("{") == content.count("}"), "unbalanced braces"
# framework build phase must point at the repo root gradlew after the module move
assert '../../gradlew' in content, "pbxproj gradlew path not updated for app-ios layout"
print("        OK (pbxproj ids + braces + gradle path valid)")
EOF
if command -v plutil >/dev/null 2>&1; then
  plutil -lint app-ios/iosApp/iosApp/Info.plist
fi

echo "  [6/7] GitHub workflow YAML parse"
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

echo "  [7/7] Background audio configured on iOS"
grep -q "UIBackgroundModes" app-ios/iosApp/iosApp/Info.plist
grep -q "<string>audio</string>" app-ios/iosApp/iosApp/Info.plist
echo "        OK"

if command -v ./gradlew >/dev/null 2>&1; then
  echo "==> Gradle: shared module JVM tests + Android compilation"
  ./gradlew :shared:jvmTest :core:jvmTest :domain:jvmTest :data:jvmTest \
            :extensions:jvmTest :player:jvmTest \
            :common:assemble :shared:assemble :core:assemble :domain:assemble \
            :data:assemble :extensions:assemble :player:assemble :composeApp:assemble \
            --stacktrace
  if [[ "$QUICK" == "0" ]]; then
    echo "==> Gradle: Android app + Wear app regression build"
    ./gradlew :app-android:assembleDebug :wearApp:assembleDebug --stacktrace
  fi
else
  echo "==> Gradle wrapper not executable; run: chmod +x ./gradlew"
fi

if [[ "$(uname -s)" == "Darwin" ]]; then
  echo "==> Gradle: iOS framework + native tests"
  ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 \
            :shared:iosSimulatorArm64Test :core:iosSimulatorArm64Test \
            :domain:iosSimulatorArm64Test :data:iosSimulatorArm64Test \
            :extensions:iosSimulatorArm64Test --stacktrace
  echo "==> Xcode: build the iOS app for the simulator"
  SIMULATOR=$(xcrun simctl list devices available | grep -oE 'iPhone [^(]+' | head -1 | sed 's/[[:space:]]*$//')
  xcodebuild -project app-ios/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -destination "platform=iOS Simulator,name=$SIMULATOR" \
    -derivedDataPath build/DerivedData \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
else
  echo "==> (macOS only steps skipped on $(uname -s))"
fi

echo "All verifications passed."
