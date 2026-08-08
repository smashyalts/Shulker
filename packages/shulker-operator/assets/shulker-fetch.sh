#!/bin/sh
# Shared download helpers, sourced by the proxy and server init scripts.
#
# Every entry in the SHULKER_*_URLS variables is `<url>` or `<url>|<sha256>`,
# separated by `;`. `|` cannot appear unencoded in a URL, so the split is
# unambiguous.
#
# Anything downloaded here ends up executing inside the game server Pod, so a
# declared digest is enforced: a mismatch aborts the init container and the Pod
# never starts. Downloads are staged to a temporary file first because a stream
# piped straight into `tar` cannot be verified before it is unpacked.

# Deliberately POSIX sh. These scripts run under BusyBox ash in the init
# container, where `[ x == y ]`, `${var//a/b}` and `set -o pipefail` only work
# because Alpine happens to build BusyBox with bash compatibility enabled.

shulker_url_of() {
  printf '%s' "${1%%|*}"
}

shulker_sha256_of() {
  case "$1" in
    *'|'*) printf '%s' "${1#*|}" ;;
    *) printf '' ;;
  esac
}

# shulker_download <spec> <destination-file>
shulker_download() {
  spec="$1"
  destination="$2"

  url="$(shulker_url_of "${spec}")"
  expected_sha256="$(shulker_sha256_of "${spec}")"

  wget -q -O "${destination}" "${url}"

  if [ -n "${expected_sha256}" ]; then
    actual_sha256="$(sha256sum "${destination}" | cut -d' ' -f1)"

    if [ "${actual_sha256}" != "${expected_sha256}" ]; then
      # The URL may embed Maven credentials, so report the filename only.
      echo "shulker: checksum mismatch for $(basename "${destination}")" >&2
      echo "shulker:   expected ${expected_sha256}" >&2
      echo "shulker:   actual   ${actual_sha256}" >&2
      rm -f "${destination}"
      exit 1
    fi
  else
    echo "shulker: warning: no sha256 declared for $(basename "${destination}"), integrity not verified" >&2
  fi
}

# shulker_download_into <spec> <target-directory>
# Keeps the filename the URL ends with, matching `wget`'s default behaviour.
shulker_download_into() {
  spec="$1"
  directory="$2"

  url="$(shulker_url_of "${spec}")"
  filename="${url##*/}"
  filename="${filename%%\?*}"

  if [ -z "${filename}" ]; then
    echo "shulker: cannot derive a filename from the supplied URL" >&2
    exit 1
  fi

  mkdir -p "${directory}"
  shulker_download "${spec}" "${directory}/${filename}"
}

# shulker_extract_tarball <spec> <target-directory>
shulker_extract_tarball() {
  spec="$1"
  directory="$2"

  archive="$(mktemp)"
  shulker_download "${spec}" "${archive}"

  mkdir -p "${directory}"
  tar -xzf "${archive}" -C "${directory}"
  rm -f "${archive}"
}

# shulker_for_each_spec <semicolon-separated-list> <function-name> <argument>
shulker_for_each_spec() {
  list="$1"
  action="$2"
  argument="$3"

  # Splitting on `;` via IFS rather than bash pattern substitution.
  old_ifs="${IFS}"
  IFS=';'
  for spec in ${list}; do
    IFS="${old_ifs}"
    [ -n "${spec}" ] || continue
    "${action}" "${spec}" "${argument}"
    IFS=';'
  done
  IFS="${old_ifs}"
}
