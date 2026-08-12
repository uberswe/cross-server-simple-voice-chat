package com.uberswe.crossvoicechat;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.*;
import net.minecraft.server.level.ServerPlayer;

/**
 * Simple Voice Chat addon entry point. Discovered automatically by SVC through
 * the {@code @ForgeVoicechatPlugin} annotation; wires SVC's server events into
 * the group sync (Redis) and audio relay (UDP) managers.
 */
@ForgeVoicechatPlugin
public class CrossVoicechatPlugin implements VoicechatPlugin {

    private static VoicechatServerApi serverApi;
    private GroupSyncManager groupSync;
    private AudioRelayManager audioRelay;

    @Override
    public String getPluginId() {
        return "crossvoicechat";
    }

    @Override
    public void initialize(VoicechatApi api) {
        CrossVoiceChat.LOGGER.info("Cross-server voice chat plugin initializing");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
        registration.registerEvent(CreateGroupEvent.class, this::onCreateGroup);
        registration.registerEvent(JoinGroupEvent.class, this::onJoinGroup);
        registration.registerEvent(LeaveGroupEvent.class, this::onLeaveGroup);
        registration.registerEvent(RemoveGroupEvent.class, this::onRemoveGroup);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        registration.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        serverApi = event.getVoicechat();
        try {
            groupSync = new GroupSyncManager(serverApi);
            audioRelay = new AudioRelayManager(groupSync);
            groupSync.start();
            audioRelay.start();
            CrossVoiceChat.LOGGER.info("Cross-server voice chat started");
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.error("Failed to start cross-server voice chat", e);
        }
    }

    private void onServerStopped(VoicechatServerStoppedEvent event) {
        if (audioRelay != null) audioRelay.stop();
        if (groupSync != null) groupSync.stop();
        serverApi = null;
        CrossVoiceChat.LOGGER.info("Cross-server voice chat stopped");
    }

    private void onCreateGroup(CreateGroupEvent event) {
        if (groupSync == null) return;
        groupSync.onLocalGroupCreated(event.getGroup());
        if (event.getConnection() != null) {
            ServerPlayer sp = (ServerPlayer) event.getConnection().getPlayer().getPlayer();
            groupSync.onLocalPlayerJoinedGroup(
                    event.getGroup(),
                    sp.getUUID(),
                    sp.getGameProfile().getName()
            );
        }
    }

    private void onJoinGroup(JoinGroupEvent event) {
        if (groupSync != null && event.getConnection() != null) {
            ServerPlayer sp = (ServerPlayer) event.getConnection().getPlayer().getPlayer();
            groupSync.onLocalPlayerJoinedGroup(
                    event.getGroup(),
                    sp.getUUID(),
                    sp.getGameProfile().getName()
            );
        }
    }

    private void onLeaveGroup(LeaveGroupEvent event) {
        if (groupSync != null && event.getConnection() != null) {
            groupSync.onLocalPlayerLeftGroup(
                    event.getGroup(),
                    event.getConnection().getPlayer().getUuid()
            );
        }
    }

    private void onRemoveGroup(RemoveGroupEvent event) {
        if (groupSync != null) {
            groupSync.onLocalGroupRemoved(event.getGroup().getId());
        }
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (audioRelay != null && event.getSenderConnection() != null) {
            audioRelay.onVoicePacket(event);
        }
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        if (groupSync != null) {
            groupSync.syncAllGroupsToPlayer(event.getConnection());
        }
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        if (groupSync != null) {
            groupSync.onPlayerDisconnected(event.getPlayerUuid());
        }
    }

    public static VoicechatServerApi getServerApi() {
        return serverApi;
    }
}
