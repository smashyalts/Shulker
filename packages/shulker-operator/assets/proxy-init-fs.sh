#!/bin/sh
set -eu

. "${SHULKER_CONFIG_DIR}/shulker-fetch.sh"

cp "${SHULKER_CONFIG_DIR}/probe-readiness.sh" "${SHULKER_PROXY_DATA_DIR}/probe-readiness.sh"
base64 -d < "${SHULKER_CONFIG_DIR}/server-icon.png" > "${SHULKER_PROXY_DATA_DIR}/server-icon.png"

if [ "${SHULKER_VERSION_CHANNEL}" = "Velocity" ]; then
  cp "${SHULKER_CONFIG_DIR}/velocity-config.toml" "${SHULKER_PROXY_DATA_DIR}/velocity.toml"
  echo "dummy" > "${SHULKER_PROXY_DATA_DIR}/forwarding.secret"
else
  cp "${SHULKER_CONFIG_DIR}/bungeecord-config.yml" "${SHULKER_PROXY_DATA_DIR}/config.yml"
fi

if [ -n "${SHULKER_PROXY_PLUGIN_URLS:-}" ]; then
  shulker_for_each_spec "${SHULKER_PROXY_PLUGIN_URLS}" shulker_download_into \
    "${SHULKER_PROXY_DATA_DIR}/plugins"
fi

if [ -n "${SHULKER_PROXY_PATCH_URLS:-}" ]; then
  shulker_for_each_spec "${SHULKER_PROXY_PATCH_URLS}" shulker_extract_tarball \
    "${SHULKER_PROXY_DATA_DIR}"
fi
