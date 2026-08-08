#!/bin/sh
set -eu

. "${SHULKER_CONFIG_DIR}/shulker-fetch.sh"

cp "${SHULKER_CONFIG_DIR}/server.properties" "${SHULKER_SERVER_CONFIG_DIR}/server.properties"

case "${SHULKER_VERSION_CHANNEL}" in
  Paper | Folia | Purpur | Pufferfish | Minestom)
    cp "${SHULKER_CONFIG_DIR}/bukkit-config.yml" "${SHULKER_SERVER_CONFIG_DIR}/bukkit.yml"
    cp "${SHULKER_CONFIG_DIR}/spigot-config.yml" "${SHULKER_SERVER_CONFIG_DIR}/spigot.yml"
    mkdir -p "${SHULKER_SERVER_CONFIG_DIR}/config"
    cp "${SHULKER_CONFIG_DIR}/paper-global-config.yml" "${SHULKER_SERVER_CONFIG_DIR}/config/paper-global.yml"
    ;;
esac

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
