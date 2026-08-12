package com.uberswe.crossvoicechat;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Mod configuration. Every value can also be provided as an environment
 * variable (useful in containerized setups), which takes precedence over the
 * config file: SERVER_NAME, REDIS_HOST, REDIS_PORT, REDIS_PASSWORD,
 * REDIS_PREFIX, VOICE_RELAY_PORT, VOICE_RELAY_HOST.
 */
public final class Config {

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<String> SERVER_NAME;
    private static final ModConfigSpec.ConfigValue<String> REDIS_HOST;
    private static final ModConfigSpec.IntValue REDIS_PORT;
    private static final ModConfigSpec.ConfigValue<String> REDIS_PASSWORD;
    private static final ModConfigSpec.ConfigValue<String> REDIS_PREFIX;
    private static final ModConfigSpec.IntValue VOICE_RELAY_PORT;
    private static final ModConfigSpec.ConfigValue<String> VOICE_RELAY_HOST;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("server");
        SERVER_NAME = builder
                .comment("Unique name of THIS backend server (e.g. \"survival\", \"creative\").",
                        "Must differ between every server sharing the same Redis instance.")
                .define("name", "server1");
        builder.pop();

        builder.push("redis");
        REDIS_HOST = builder
                .comment("Redis host used for group sync (shared by all backend servers)")
                .define("host", "localhost");
        REDIS_PORT = builder.defineInRange("port", 6379, 1, 65535);
        REDIS_PASSWORD = builder
                .comment("Redis password (empty for no auth)")
                .define("password", "");
        REDIS_PREFIX = builder
                .comment("Prefix for every Redis key/channel this mod uses")
                .define("prefix", "crossvoice:");
        builder.pop();

        builder.push("relay");
        VOICE_RELAY_PORT = builder
                .comment("UDP port for cross-server voice audio relay (0 = auto-assign).",
                        "Backend servers must be able to reach each other on this port.")
                .defineInRange("port", 0, 0, 65535);
        VOICE_RELAY_HOST = builder
                .comment("Hostname or IP the OTHER servers can reach this server's relay on.",
                        "Empty = use this machine's hostname.")
                .define("host", "");
        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    public static String getServerName() {
        String env = env("SERVER_NAME");
        return env != null ? env : SERVER_NAME.get();
    }

    public static String getRedisHost() {
        String env = env("REDIS_HOST");
        return env != null ? env : REDIS_HOST.get();
    }

    public static int getRedisPort() {
        String env = env("REDIS_PORT");
        return env != null ? Integer.parseInt(env) : REDIS_PORT.get();
    }

    /** Redis password, or null when no auth is configured. */
    public static String getRedisPassword() {
        String env = env("REDIS_PASSWORD");
        String value = env != null ? env : REDIS_PASSWORD.get();
        return value.isEmpty() ? null : value;
    }

    public static String getRedisPrefix() {
        String env = env("REDIS_PREFIX");
        return env != null ? env : REDIS_PREFIX.get();
    }

    public static int getVoiceRelayPort() {
        String env = env("VOICE_RELAY_PORT");
        return env != null ? Integer.parseInt(env) : VOICE_RELAY_PORT.get();
    }

    /** Relay hostname other servers should use to reach this one, or null to auto-detect. */
    public static String getVoiceRelayHost() {
        String env = env("VOICE_RELAY_HOST");
        if (env != null) return env;
        String value = VOICE_RELAY_HOST.get();
        return value.isEmpty() ? null : value;
    }
}
