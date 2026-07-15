#!/usr/bin/env bash
#
# Copyright (C) 2026 FebriCahyaa
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Toolchain-drift guard.
#
# The JDK major version is declared in several places that MUST agree. This
# script derives the canonical version from the Gradle daemon toolchain and
# fails, with an actionable message, if any CI workflow or the Kotlin toolchain
# disagrees. Run it locally the same way CI does: scripts/check-toolchain.sh
#
# Adding a new place that pins the JDK? Add it to CHECKED_FILES below.

set -euo pipefail

cd "$(dirname "$0")/../.."

CANON_FILE="gradle/gradle-daemon-jvm.properties"

fail() {
    # GitHub annotation when running in Actions; plain text otherwise.
    if [ -n "${GITHUB_ACTIONS:-}" ]; then
        echo "::error file=${1}::${2}"
    else
        echo "ERROR (${1}): ${2}" >&2
    fi
    exit 1
}

[ -f "$CANON_FILE" ] || fail "$CANON_FILE" "canonical toolchain file is missing"

# Canonical version: toolchainVersion=NN
CANON="$(sed -n 's/^toolchainVersion=\([0-9][0-9]*\).*/\1/p' "$CANON_FILE")"
[ -n "$CANON" ] || fail "$CANON_FILE" "could not read toolchainVersion"

echo "Canonical JDK toolchain: ${CANON} (from ${CANON_FILE})"

status=0

# Kotlin jvmToolchain(NN) in the pure module.
KT_FILE="telemetry/build.gradle.kts"
if [ -f "$KT_FILE" ]; then
    KT="$(sed -n 's/.*jvmToolchain(\([0-9][0-9]*\)).*/\1/p' "$KT_FILE" | head -n1)"
    if [ -n "$KT" ] && [ "$KT" != "$CANON" ]; then
        echo "::error file=${KT_FILE}::jvmToolchain(${KT}) disagrees with canonical JDK ${CANON}"
        status=1
    fi
fi

# Every workflow that pins a java-version must pin the canonical version.
for wf in .github/workflows/*.yml; do
    [ -f "$wf" ] || continue
    # Extract each pinned major version (handles "21", '21', 21).
    while IFS= read -r ver; do
        [ -n "$ver" ] || continue
        if [ "$ver" != "$CANON" ]; then
            echo "::error file=${wf}::java-version '${ver}' disagrees with canonical JDK ${CANON}"
            status=1
        fi
    done < <(sed -n "s/.*java-version:[[:space:]]*['\"]\{0,1\}\([0-9][0-9]*\)['\"]\{0,1\}.*/\1/p" "$wf")
done

if [ "$status" -ne 0 ]; then
    echo ""
    echo "Toolchain drift detected. Align every location above with JDK ${CANON}," >&2
    echo "or, if you are intentionally bumping the toolchain, update ${CANON_FILE}" >&2
    echo "first and then every consumer to match." >&2
    exit 1
fi

echo "OK: all toolchain declarations agree on JDK ${CANON}."
