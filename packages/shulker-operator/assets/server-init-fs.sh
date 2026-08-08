#!/bin/sh
set -eu

. "${SHULKER_CONFIG_DIR}/shulker-fetch.sh"

cp "${SHULKER_CONFIG_DIR}/server.properties" "${SHULKER_SERVER_CONFIG_DIR}/server.properties"

# The operator decides the layout from the flavour table and passes it in, so
# adding a server flavour does not mean editing a channel list here.
if [ "${SHULKER_SERVER_CONFIG_LAYOUT:-bukkit}" = "bukkit" ]; then
  cp "${SHULKER_CONFIG_DIR}/bukkit-config.yml" "${SHULKER_SERVER_CONFIG_DIR}/bukkit.yml"
  cp "${SHULKER_CONFIG_DIR}/spigot-config.yml" "${SHULKER_SERVER_CONFIG_DIR}/spigot.yml"
  mkdir -p "${SHULKER_SERVER_CONFIG_DIR}/config"
  cp "${SHULKER_CONFIG_DIR}/paper-global-config.yml" "${SHULKER_SERVER_CONFIG_DIR}/config/paper-global.yml"
fi

if [ -n "${SHULKER_SERVER_WORLD_URL:-}" ]; then
  shulker_extract_tarball "${SHULKER_SERVER_WORLD_URL}" "${SHULKER_SERVER_CONFIG_DIR}"
fi

if [ -n "${SHULKER_SERVER_PLUGIN_URLS:-}" ]; then
  shulker_for_each_spec "${SHULKER_SERVER_PLUGIN_URLS}" shulker_download_into \
    "${SHULKER_SERVER_CONFIG_DIR}/plugins"
fi

if [ -n "${SHULKER_SERVER_PATCH_URLS:-}" ]; then
  shulker_for_each_spec "${SHULKER_SERVER_PATCH_URLS}" shulker_extract_tarball \
    "${SHULKER_SERVER_CONFIG_DIR}"
fi
