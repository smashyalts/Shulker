use shulker_crds::v1alpha1::minecraft_server::MinecraftServerVersion;

/// How the init container should lay out the generated configuration files.
///
/// Emitted as `SHULKER_SERVER_CONFIG_LAYOUT` so that adding a flavour is a
/// change to the table below rather than to the init script.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ConfigLayout {
    /// `bukkit.yml`, `spigot.yml` and `config/paper-global.yml` alongside
    /// `server.properties`. Used by Paper and everything forked from it.
    Bukkit,
    /// `server.properties` only.
    Plain,
}

impl ConfigLayout {
    pub fn as_env_value(&self) -> &'static str {
        match self {
            ConfigLayout::Bukkit => "bukkit",
            ConfigLayout::Plain => "plain",
        }
    }
}

/// Everything that varies between server flavours, in one place.
///
/// This used to be spread across four separate `match` arms on
/// `MinecraftServerVersion` plus a hardcoded channel list inside the init
/// script, so adding a flavour meant finding all five and keeping them in sync.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Flavor {
    /// Value of the `TYPE` environment variable understood by the
    /// `itzg/minecraft-server` image.
    pub image_type: &'static str,
    /// Environment variable the image reads the version from.
    pub version_env: &'static str,
    /// Which Shulker agent build to install, if the flavour supports plugins.
    pub agent_platform: Option<&'static str>,
    pub config_layout: ConfigLayout,
    /// Whether a `version.customJar` must be supplied for the flavour to run.
    pub requires_custom_jar: bool,
}

impl Flavor {
    pub fn of(channel: &MinecraftServerVersion) -> Flavor {
        match channel {
            MinecraftServerVersion::Paper => Flavor {
                image_type: "PAPER",
                version_env: "VERSION",
                agent_platform: Some("paper"),
                config_layout: ConfigLayout::Bukkit,
                requires_custom_jar: false,
            },
            MinecraftServerVersion::Folia => Flavor {
                image_type: "FOLIA",
                version_env: "VERSION",
                agent_platform: Some("paper"),
                config_layout: ConfigLayout::Bukkit,
                requires_custom_jar: false,
            },
            // Purpur is a Paper fork, so it takes the Paper agent build and the
            // Paper configuration layout unchanged.
            MinecraftServerVersion::Purpur => Flavor {
                image_type: "PURPUR",
                version_env: "VERSION",
                agent_platform: Some("paper"),
                config_layout: ConfigLayout::Bukkit,
                requires_custom_jar: false,
            },
            // Likewise Pufferfish.
            MinecraftServerVersion::Pufferfish => Flavor {
                image_type: "PUFFERFISH",
                version_env: "VERSION",
                agent_platform: Some("paper"),
                config_layout: ConfigLayout::Bukkit,
                requires_custom_jar: false,
            },
            // Minestom is not a Bukkit server: it ships no plugin API Shulker's
            // agent can target, and the image can only run it from a custom JAR.
            MinecraftServerVersion::Minestom => Flavor {
                image_type: "PAPER",
                version_env: "VERSION",
                agent_platform: None,
                config_layout: ConfigLayout::Bukkit,
                requires_custom_jar: true,
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use shulker_crds::v1alpha1::minecraft_server::MinecraftServerVersion;

    use super::{ConfigLayout, Flavor};

    const ALL_CHANNELS: [MinecraftServerVersion; 5] = [
        MinecraftServerVersion::Paper,
        MinecraftServerVersion::Folia,
        MinecraftServerVersion::Purpur,
        MinecraftServerVersion::Pufferfish,
        MinecraftServerVersion::Minestom,
    ];

    #[test]
    fn every_channel_has_a_flavor() {
        for channel in ALL_CHANNELS {
            let flavor = Flavor::of(&channel);
            assert!(!flavor.image_type.is_empty(), "{channel} has no image type");
            assert!(
                !flavor.version_env.is_empty(),
                "{channel} has no version env"
            );
        }
    }

    #[test]
    fn purpur_reuses_the_paper_agent_and_layout() {
        let purpur = Flavor::of(&MinecraftServerVersion::Purpur);
        let paper = Flavor::of(&MinecraftServerVersion::Paper);

        assert_eq!(purpur.image_type, "PURPUR");
        assert_eq!(purpur.agent_platform, paper.agent_platform);
        assert_eq!(purpur.config_layout, paper.config_layout);
        assert!(!purpur.requires_custom_jar);
    }

    #[test]
    fn only_minestom_requires_a_custom_jar() {
        for channel in ALL_CHANNELS {
            let requires = Flavor::of(&channel).requires_custom_jar;
            assert_eq!(
                requires,
                channel == MinecraftServerVersion::Minestom,
                "unexpected custom JAR requirement for {channel}"
            );
        }
    }

    #[test]
    fn only_minestom_has_no_agent() {
        for channel in ALL_CHANNELS {
            let has_agent = Flavor::of(&channel).agent_platform.is_some();
            assert_eq!(
                has_agent,
                channel != MinecraftServerVersion::Minestom,
                "unexpected agent platform for {channel}"
            );
        }
    }

    #[test]
    fn config_layout_env_values_are_what_the_init_script_matches_on() {
        assert_eq!(ConfigLayout::Bukkit.as_env_value(), "bukkit");
        assert_eq!(ConfigLayout::Plain.as_env_value(), "plain");
    }
}
