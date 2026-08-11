#!/usr/bin/env bash
#
# Boots the PRODUCED jar on a real Forge dedicated server and plays a station.
#
#   ./tools/check-server-jar.sh [jar] [station-url]
#
# Why this exists, and why `./gradlew :forge:build` passing is not the same thing:
#
#   * runClient runs from the source set, so it never loads the jar. Every jar this project has
#     produced up to 2026-08-11 was missing :core entirely and would have died with
#     NoClassDefFoundError; the build was green throughout.
#   * runClient is a CLIENT. Forge's RuntimeDistCleaner only refuses net.minecraft.client on a
#     DEDICATED_SERVER, so a client class reachable from the entry class crashes servers and nothing
#     else. That bug shipped too, and this script is what found it.
#
# Both failure modes are invisible to the build and to singleplayer, and both make the mod
# completely unusable for the thing it is for — several people listening to one server.
#
# Not wired into `check`: it downloads a Forge server (~150 MB, cached in .build/) and needs
# outbound network for the station. Run it before releasing a jar.
#
# Exits non-zero, loudly, on any of: mod failed to construct, block not registered, no PLAYING.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/.build"

MC_VERSION="1.20.1"
FORGE_VERSION="47.4.0"
SERVER_DIR="$BUILD/testserver-${MC_VERSION}-${FORGE_VERSION}"
INSTALLER="$BUILD/forge-${MC_VERSION}-${FORGE_VERSION}-installer.jar"

JAR="${1:-}"
STATION="${2:-https://ice1.somafm.com/groovesalad-128-mp3}"

if [[ -z "$JAR" ]]; then
    JAR="$(ls -t "$ROOT"/forge/build/libs/*.jar 2>/dev/null | grep -v -- '-slim\.jar$' | head -1 || true)"
fi
if [[ ! -f "$JAR" ]]; then
    echo "no jar to test: pass one, or run ./gradlew :forge:build first" >&2
    exit 2
fi

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! "$JAVA" -version 2>&1 | grep -q '"17'; then
    echo "needs a JDK 17 on JAVA_HOME (see README)" >&2
    exit 2
fi

mkdir -p "$BUILD"

if [[ ! -d "$SERVER_DIR/libraries" ]]; then
    echo "==> installing Forge $MC_VERSION-$FORGE_VERSION server (once, cached)"
    [[ -f "$INSTALLER" ]] || curl -fsSL -o "$INSTALLER" \
        "https://maven.minecraftforge.net/net/minecraftforge/forge/${MC_VERSION}-${FORGE_VERSION}/forge-${MC_VERSION}-${FORGE_VERSION}-installer.jar"
    mkdir -p "$SERVER_DIR"
    (cd "$SERVER_DIR" && "$JAVA" -jar "$INSTALLER" --installServer . > install.log 2>&1) \
        || { echo "forge install failed, see $SERVER_DIR/install.log" >&2; exit 1; }
    echo "eula=true" > "$SERVER_DIR/eula.txt"
    # A flat world with no structures so "Preparing spawn area" is seconds rather than a minute.
    # online-mode=false because nothing here logs in, and the auth round trip would just be latency.
    cat > "$SERVER_DIR/server.properties" <<'EOF'
online-mode=false
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=4
max-tick-time=120000
EOF
fi

# A world left over from a previous run would still hold the radio placed by that run, which starts
# playing before the checks below begin and makes a stale PASS possible.
rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods"
mkdir -p "$SERVER_DIR/mods"
cp "$JAR" "$SERVER_DIR/mods/"

LOG="$SERVER_DIR/check.log"
FIFO="$SERVER_DIR/console.fifo"
rm -f "$FIFO" "$LOG"
mkfifo "$FIFO"

echo "==> booting $(basename "$JAR")"
( cd "$SERVER_DIR" && timeout 300 "$JAVA" -Xmx2G \
      "@libraries/net/minecraftforge/forge/${MC_VERSION}-${FORGE_VERSION}/unix_args.txt" nogui \
      < "$FIFO" > "$LOG" 2>&1 ) &
SERVER_PID=$!
exec 3> "$FIFO"

cleanup() {
    echo "stop" >&3 2>/dev/null || true
    exec 3>&- 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
    rm -f "$FIFO"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    # The interesting line is almost never the last one — a mod that fails to construct takes the
    # server down several screens later, so the tail alone points at the wrong thing.
    local cause
    cause="$(grep -m1 -E 'Failed to create mod instance|NoClassDefFoundError|invalid dist|Caused by' "$LOG" || true)"
    [[ -n "$cause" ]] && echo "  cause: $cause" >&2
    echo "  log: $LOG" >&2
    exit 1
}

# Wait for the server to finish starting rather than sleeping a guessed interval — spawn generation
# is much slower on a cold cache than a warm one.
for _ in $(seq 90); do
    grep -q 'For help, type' "$LOG" && break
    kill -0 "$SERVER_PID" 2>/dev/null || fail "server died during startup"
    sleep 1
done
grep -q 'For help, type' "$LOG" || fail "server never finished starting"

grep -q 'has failed to load correctly' "$LOG" \
    && fail "the mod did not construct$(printf '\n')$(grep -A3 'Failed to create mod instance' "$LOG" | head -4)"

echo "==> placing a radio and switching it on"
echo "setblock 0 -60 0 mmmm:radio" >&3
sleep 2
grep -q 'Unknown block type\|Could not set the block' "$LOG" && fail "mmmm:radio is not registered"

# Straight into the block entity's NBT: no player, so no right-click and no ConfigureRadio packet.
echo "data modify block 0 -60 0 Playing set value 1b" >&3

# Generous: this is a real upstream connection, and the epoch settling window (PLAN.md §4.3)
# deliberately waits for the origin's burst to stop before PLAYING.
for _ in $(seq 60); do
    grep -q ': PLAYING ' "$LOG" && break
    grep -qE ': FAILED |NoClassDefFoundError' "$LOG" && break
    sleep 1
done

if grep -q 'NoClassDefFoundError' "$LOG"; then
    fail "a class is missing from the jar$(printf '\n')$(grep -m1 -A2 NoClassDefFoundError "$LOG")"
fi
grep -q ': PLAYING ' "$LOG" || fail "the relay never reached PLAYING$(printf '\n')$(grep -E 'Radio at' "$LOG" | tail -3)"

echo "==> PASS"
grep -E 'Radio at' "$LOG" | sed 's/^/    /'
