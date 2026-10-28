#!/bin/sh
# Makes sure Lunar's 1.8 profile has the VR agent in its own JVM arguments (profile
# overrides beat the global setting, and Lunar resets the JAVA_*_OPTIONS env vars), then
# starts Lunar. Existing arguments, e.g. -Duser.timezone, are kept.
# Optional agent options: LCVR_OPTS="hudDist=1.2,scale=0.9" ./launch-lunar-vr.sh
# LCVR_DIST overrides where lcvr.jar and its native libraries are (the Nix package sets it).
set -eu
dir="$(dirname "$(readlink -f "$0")")"
db="$HOME/.lunarclient/db/profiles.db"
dist="${LCVR_DIST:-$dir/dist}"
agent="-javaagent:$dist/lcvr.jar${LCVR_OPTS:+=$LCVR_OPTS}"

sqlite() {
  if command -v sqlite3 >/dev/null 2>&1; then sqlite3 "$@"
  else nix shell nixpkgs#sqlite -c sqlite3 "$@"; fi
}

if pgrep -f "(^|/)lunarclient( |$)" >/dev/null; then
  echo "Lunar is already running; quit it fully first so the profile change sticks." >&2
  exit 1
fi

if [ -f "$db" ]; then
  # drop any previous lcvr agent argument, then append the current one
  sqlite "$db" "
    UPDATE profiles SET jvm_arguments = trim(
      coalesce((SELECT group_concat(a, ' ') FROM (
        WITH RECURSIVE s(a, rest) AS (
          SELECT '', coalesce(jvm_arguments, '') || ' '
          UNION ALL
          SELECT substr(rest, 1, instr(rest, ' ') - 1), substr(rest, instr(rest, ' ') + 1)
          FROM s WHERE rest <> '')
        SELECT a FROM s WHERE a <> '' AND instr(a, 'lcvr.jar') = 0)), '')
      || ' ' || '$agent')
    WHERE major_game_version = '1.8';"
  echo "1.8 profile JVM arguments: $(sqlite "$db" "SELECT jvm_arguments FROM profiles WHERE major_game_version = '1.8';")"
fi

# LUNAR_CMD lets non-NixOS installs point at their Lunar binary or AppImage
exec ${LUNAR_CMD:-lunarclient} "$@"
