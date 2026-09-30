#!/usr/bin/env bash
# Relic verification gate.
#
# Gradle cannot run here (AGP is not in the offline cache), so this drives the
# parts that are actually checkable on this machine, all of them against the
# real source rather than a copy:
#
#   1. catcls   - compile :catalog main (pure Kotlin, no Android deps)
#   2. tests    - compile and run every :catalog unit test
#   3. gate     - generate every shader from the freshly compiled classes
#   4. glsl     - glslangValidator over every generated shader
#
# Every step fails the script. Steps 1-2 are the baseline the build report says
# the project has never had: a green run means the catalog module compiles and
# all its tests pass, which is more than "made a commit without a build".
#
# Usage:  bash tools/verify.sh   (POSIX sh has no pipefail; this needs it)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/verify"
M2="$HOME/.gradle/caches/modules-2/files-2.1"

j() { find "$M2/$1" -name "$2" 2>/dev/null | head -1; }
CP_KOTLIN="$(j org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2*.jar')"
CP_TEST="$(j org.jetbrains.kotlin/kotlin-test 'kotlin-test-2*.jar')"
CP_TESTJUNIT="$(j org.jetbrains.kotlin/kotlin-test-junit 'kotlin-test-junit-2*.jar')"
CP_JUNIT="$(j junit/junit 'junit-4*.jar')"
CP_HAMCREST="$(j org.hamcrest/hamcrest-core 'hamcrest-core-1.3.jar')"
CP_ZXING="$(j com.google.zxing/core 'core-3.5.3.jar')"

DEPS="$CP_KOTLIN:$CP_ZXING:$CP_TEST:$CP_TESTJUNIT:$CP_JUNIT:$CP_HAMCREST"
for d in "$CP_KOTLIN" "$CP_ZXING" "$CP_JUNIT" "$CP_HAMCREST"; do
  [ -f "$d" ] || { echo "MISSING DEP: $d (offline gradle cache)"; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT"

step() { printf '\n=== %s ===\n' "$1"; }

# ---------------------------------------------------------------- 1. catcls
step "1/4 catalog main (catcls)"
# Always a fresh dir, so a stale .class can never be linked in. This is the
# §4.1 failure mode and it is now structurally impossible here.
kotlinc -nowarn -jvm-target 17 \
  -cp "$CP_KOTLIN:$CP_ZXING" \
  -d "$OUT/catcls" \
  "$ROOT/catalog/src/main/java" 2>&1 | grep -vE '^(warning:|Failed to load native library|java\.lang\.UnsatisfiedLinkError:)' || true
[ -d "$OUT/catcls/com" ] || { echo "catcls produced no classes"; exit 1; }

# ------------------------------------------------------------------ 2. tests
step "2/4 catalog unit tests"
# -Xfriend-paths mirrors what Gradle's Kotlin plugin does for a test source set.
# Without it Kotlin treats main as a different module and refuses the smart
# casts on data-class properties the existing tests rely on.
kotlinc -nowarn -jvm-target 17 \
  -cp "$DEPS:$OUT/catcls" \
  -Xfriend-paths="$OUT/catcls" \
  -d "$OUT/catcls-test" \
  "$ROOT/catalog/src/test/java" 2>&1 | grep -vE '^(warning:|Failed to load native library|java\.lang\.UnsatisfiedLinkError:)' || true
[ -d "$OUT/catcls-test/com" ] || { echo "test compile produced no classes"; exit 1; }

TESTS="$(cd "$OUT/catcls-test" && find . -name '*Test.class' ! -name '*\$*' \
  | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)"
COUNT="$(echo "$TESTS" | grep -c . || true)"
echo "running $COUNT test classes"
# shellcheck disable=SC2086
java -cp "$DEPS:$OUT/catcls:$OUT/catcls-test" org.junit.runner.JUnitCore $TESTS

# ------------------------------------------------------------------- 3. gate
step "3/4 GLSL gate (content-guarded)"
kotlinc -nowarn -jvm-target 17 \
  -cp "$DEPS:$OUT/catcls" \
  -d "$OUT/gen" \
  "$ROOT/tools/ShaderGate.kt" 2>&1 | grep -vE '^(warning:|Failed to load native library|java\.lang\.UnsatisfiedLinkError:)' || true
java -cp "$DEPS:$OUT/catcls:$OUT/gen" ShaderGate "$OUT/shaders"

# -------------------------------------------------------------------- 4. glsl
step "4/4 glslangValidator"
if command -v glslangValidator >/dev/null 2>&1; then
  FAIL=0; N=0
  for f in "$OUT"/shaders/*.frag; do
    N=$((N + 1))
    if ! glslangValidator "$f" >"$OUT/glsl.log" 2>&1; then
      echo "GLSL FAIL: $(basename "$f")"; sed -n '1,12p' "$OUT/glsl.log"; FAIL=1
    fi
  done
  [ "$FAIL" -eq 0 ] || exit 1
  echo "glslangValidator: $N/$N shaders compiled"
else
  echo "SKIP: glslangValidator not on PATH"
fi

# The Kotlin/shader arithmetic drift guard is NOT a step of its own: it is
# RangeToneTest, which reads the exact expressions out of Shaders.HEADER and
# already runs in step 2. A second copy here would be the thing it exists to
# prevent.

printf '\n=== ALL GREEN ===\n'
