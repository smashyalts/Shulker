#!/bin/sh
# Functional check of assets/shulker-fetch.sh with a stubbed `wget`.
set -u

ASSETS="$1"
WORK="$(mktemp -d)"
STUB="${WORK}/bin"
CONTENT="hello shulker"

mkdir -p "${STUB}"
cat > "${STUB}/wget" <<'STUBEOF'
#!/bin/sh
# usage: wget -q -O <dest> <url>
dest=""
while [ $# -gt 0 ]; do
  case "$1" in
    -O) dest="$2"; shift 2 ;;
    -q) shift ;;
    *) url="$1"; shift ;;
  esac
done
printf '%s' "${WGET_STUB_BODY}" > "${dest}"
STUBEOF
chmod +x "${STUB}/wget"
PATH="${STUB}:${PATH}"
export PATH

. "${ASSETS}/shulker-fetch.sh"

GOOD_SHA="$(printf '%s' "${CONTENT}" | sha256sum | cut -d' ' -f1)"
BAD_SHA="$(printf '%s' "not the content" | sha256sum | cut -d' ' -f1)"
WGET_STUB_BODY="${CONTENT}"
export WGET_STUB_BODY

fail=0
check() {
  if [ "$2" = "$3" ]; then
    echo "  PASS $1"
  else
    echo "  FAIL $1: expected [$3] got [$2]"
    fail=1
  fi
}

echo "-- parsing"
check "url without digest"  "$(shulker_url_of 'https://e.com/a.jar')"                "https://e.com/a.jar"
check "url with digest"     "$(shulker_url_of "https://e.com/a.jar|${GOOD_SHA}")"    "https://e.com/a.jar"
check "digest absent"       "$(shulker_sha256_of 'https://e.com/a.jar')"             ""
check "digest present"      "$(shulker_sha256_of "https://e.com/a.jar|${GOOD_SHA}")" "${GOOD_SHA}"

echo "-- matching digest is accepted"
( shulker_download "https://e.com/a.jar|${GOOD_SHA}" "${WORK}/ok.jar" ) 2>/dev/null
check "exit status" "$?" "0"
check "file content" "$(cat "${WORK}/ok.jar" 2>/dev/null)" "${CONTENT}"

echo "-- mismatching digest is rejected"
( shulker_download "https://e.com/a.jar|${BAD_SHA}" "${WORK}/bad.jar" ) 2>/dev/null
check "exit status" "$?" "1"
if [ -f "${WORK}/bad.jar" ]; then
  echo "  FAIL corrupt file left on disk"
  fail=1
else
  echo "  PASS corrupt file removed"
fi

echo "-- download_into derives the filename"
shulker_download_into "https://e.com/some/path/plugin.jar|${GOOD_SHA}" "${WORK}/plugins" 2>/dev/null
check "downloaded name" "$(ls "${WORK}/plugins")" "plugin.jar"

echo "-- list splitting on ';'"
COUNT_FILE="${WORK}/count"
: > "${COUNT_FILE}"
record() { echo "$1" >> "${COUNT_FILE}"; }
shulker_for_each_spec "https://e.com/a.jar;https://e.com/b.jar|${GOOD_SHA};" record ignored
check "entries visited" "$(wc -l < "${COUNT_FILE}" | tr -d ' ')" "2"
check "second entry intact" "$(sed -n 2p "${COUNT_FILE}")" "https://e.com/b.jar|${GOOD_SHA}"

echo "-- tarball extraction verifies before unpacking"
mkdir -p "${WORK}/src/world" && echo payload > "${WORK}/src/world/level.dat"
( cd "${WORK}/src" && tar -czf "${WORK}/world.tar.gz" world )
TAR_SHA="$(sha256sum "${WORK}/world.tar.gz" | cut -d' ' -f1)"
WGET_STUB_BODY="$(cat "${WORK}/world.tar.gz")"

( shulker_extract_tarball "https://e.com/w.tar.gz|$(printf 'c%.0s' $(seq 64))" "${WORK}/out" ) 2>/dev/null
check "bad tarball rejected" "$?" "1"
if [ -d "${WORK}/out/world" ]; then
  echo "  FAIL unverified archive was extracted"
  fail=1
else
  echo "  PASS nothing extracted from unverified archive"
fi

rm -rf "${WORK}"
if [ "${fail}" -eq 0 ]; then echo "ALL PASS"; else echo "FAILURES"; fi
exit "${fail}"
