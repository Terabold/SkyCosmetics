#!/usr/bin/env bash
# Prints Minecraft/Fabric signatures and files listed in the probe file, for coding sessions that
# cannot download Minecraft themselves (cloud sessions with a locked-down network). CI already has
# every jar after `./gradlew build`; the session reads this step's log.
#
# Probe file lines:
#   javap <class> [grep-regex]   members of a class (only lines matching the regex, when given)
#   classes <regex>              class files in the Minecraft/Fabric jars whose path matches
#   asset <path>                 a file from the Minecraft jars, e.g. assets/minecraft/items/player_head.json
#   code <class> <a|b|...>       bytecode (javap -c) of the methods whose header contains a or b...
#   grepasset <path> <regex>     only the matching lines of a big file in the jars (a lang file...)
#   url <url>                    the body of a URL (maven metadata, a pom...)
#   addjar <url>                 downloads a jar and adds it to the classpath of the lines after it
#   # ...                        a comment
set -u
probe="${1:-.github/api-probe.txt}"
jars=$(find "$HOME/.gradle/caches/fabric-loom" "$HOME/.gradle/caches/modules-2/files-2.1/net.fabricmc"* \
  "$HOME/.gradle/caches/modules-2/files-2.1/org.joml" "$HOME/.gradle/caches/modules-2/files-2.1/com.mojang" \
  "$HOME/.gradle/caches/modules-2/files-2.1/io.github.llamalad7" \
  -name '*.jar' ! -name '*-sources.jar' 2>/dev/null | sort -u)
cp=$(echo "$jars" | tr '\n' ':')
echo "=== jars"
echo "$jars" | grep -iE 'minecraft|fabric-api|fabric-loader' | sed 's|.*/||' | sort -u | head -80
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in ''|'#'*) continue ;; esac
  set -- $line
  kind=$1; shift
  echo "=== $kind $*"
  case "$kind" in
    javap)
      if [ $# -ge 2 ]; then
        cls=$1; shift
        javap -p -cp "$cp" "$cls" 2>&1 | awk -v re="$*" 'NR <= 2 || $0 ~ re' # the declaration, then matches
      else
        javap -p -cp "$cp" "$1" 2>&1
      fi ;;
    classes)
      for j in $jars; do
        unzip -Z1 "$j" 2>/dev/null | grep -E "$1" | grep '\.class$' | sed "s|^|$(basename "$j"): |"
      done | sort -u | head -200 ;;
    code)
      cls=$1; shift
      # Plain substrings, '|' between alternatives: method headers are full of regex characters.
      javap -c -p -cp "$cp" "$cls" 2>&1 | awk -v pats="$*" 'BEGIN { n = split(pats, p, "|") }
        /^  [^ ]/ { show = 0; for (i = 1; i <= n; i++) if (index($0, p[i])) show = 1 } show' ;;
    grepasset)
      path=$1; shift
      for j in $jars; do
        if unzip -Z1 "$j" 2>/dev/null | grep -qx "$path"; then unzip -p "$j" "$path" | grep -E "$*" | head -200; break; fi
      done ;;
    addjar)
      f="/tmp/probe-$(basename "$1")"
      curl -sSL -m 60 -o "$f" "$1" && cp="$cp:$f" && echo "added $(basename "$1") ($(stat -c %s "$f") bytes)" ;;
    asset)
      found=0
      for j in $jars; do
        if unzip -Z1 "$j" 2>/dev/null | grep -qx "$1"; then unzip -p "$j" "$1"; echo; found=1; break; fi
      done
      [ $found = 1 ] || echo "(not found)" ;;
    url)
      curl -sSL -m 30 "$1" | head -c 20000; echo ;;
    *) echo "(unknown probe '$kind')" ;;
  esac
done < "$probe"
echo "=== end of probe"
