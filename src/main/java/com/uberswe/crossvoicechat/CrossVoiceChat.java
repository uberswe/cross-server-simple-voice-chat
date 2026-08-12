package com.uberswe.crossvoicechat;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-Server Simple Voice Chat: makes Simple Voice Chat groups work across
 * multiple backend servers behind a proxy (Velocity, BungeeCord, ...).
 *
 * <p>Group create/join/leave events are synchronized between servers over Redis
 * pub/sub, and group audio is relayed directly between the servers over UDP.
 * The actual voice plumbing hooks in through {@link CrossVoicechatPlugin},
 * which Simple Voice Chat discovers via its {@code @ForgeVoicechatPlugin}
 * annotation. This class only registers the config.
 */
@Mod(value = CrossVoiceChat.MOD_ID, dist = net.neoforged.api.distmarker.Dist.DEDICATED_SERVER)
public class CrossVoiceChat {

    public static final String MOD_ID = "crossvoicechat";
    public static final Logger LOGGER = LoggerFactory.getLogger("CrossVoiceChat");

    public CrossVoiceChat(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        LOGGER.info("Cross-Server Simple Voice Chat loaded");
    }
}
