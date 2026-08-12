package com.uberswe.crossvoicechat;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.PlayerStatePacket;
import de.maxhenkel.voicechat.net.RemovePlayerStatePacket;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.server.PlayerStateManager;
import net.minecraft.server.level.ServerPlayer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

public class GroupSyncManager {

    private final String CHANNEL;
    private final String GROUPS_KEY;
    private final String MEMBERS_PREFIX;
    private final String REJOIN_PREFIX;
    private static final int REJOIN_TTL_SECONDS = 30;
    private static final int GROUP_GRACE_PERIOD_SECONDS = 30;

    private final VoicechatServerApi api;
    private final String serverName;
    private JedisPool jedisPool;
    private Thread subscribeThread;
    private volatile boolean running;

    private final Set<UUID> locallyCreatedGroups = ConcurrentHashMap.newKeySet();
    private final Set<UUID> mirrorGroups = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> playerGroupMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> groupNames = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> pendingRemovals = new ConcurrentHashMap<>();
    private final Set<UUID> syncInProgress = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CrossVoice-GroupCleanup");
        t.setDaemon(true);
        return t;
    });

    private final Map<UUID, CachedServers> remoteServersCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 2000;

    private final Set<UUID> remotePlayerStates = ConcurrentHashMap.newKeySet();
    private final Set<UUID> creatingMirror = ConcurrentHashMap.newKeySet();
    private volatile Field statesField;

    public GroupSyncManager(VoicechatServerApi api) {
        this.api = api;
        this.serverName = Config.getServerName();
        String prefix = Config.getRedisPrefix();
        this.CHANNEL = prefix + "voicechat:groups";
        this.GROUPS_KEY = prefix + "voicechat:group_data";
        this.MEMBERS_PREFIX = prefix + "voicechat:members:";
        this.REJOIN_PREFIX = prefix + "voicechat:rejoin:";
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(4);
        jedisPool = new JedisPool(poolConfig,
                Config.getRedisHost(),
                Config.getRedisPort(),
                2000,
                Config.getRedisPassword());

        loadExistingGroups();

        running = true;
        subscribeThread = new Thread(this::subscribeLoop, "CrossVoice-GroupSync");
        subscribeThread.setDaemon(true);
        subscribeThread.start();
    }

    public void stop() {
        running = false;
        if (subscribeThread != null) subscribeThread.interrupt();

        scheduler.shutdownNow();
        pendingRemovals.clear();

        try (Jedis jedis = jedisPool.getResource()) {
            for (UUID groupId : locallyCreatedGroups) {
                jedis.hdel(GROUPS_KEY, groupId.toString());
                jedis.del(MEMBERS_PREFIX + groupId);
            }
            locallyCreatedGroups.clear();
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to clean up groups from Redis on shutdown", e);
        }

        for (UUID groupId : mirrorGroups) {
            api.removeGroup(groupId);
        }
        mirrorGroups.clear();
        groupNames.clear();

        for (UUID remoteUuid : remotePlayerStates) {
            removeRemotePlayerStateInternal(remoteUuid);
        }
        remotePlayerStates.clear();

        if (jedisPool != null) jedisPool.close();
    }

    private void loadExistingGroups() {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> allGroups = jedis.hgetAll(GROUPS_KEY);
            CrossVoiceChat.LOGGER.info("Loading {} existing voice group(s) from Redis", allGroups.size());
            for (Map.Entry<String, String> entry : allGroups.entrySet()) {
                UUID groupId = UUID.fromString(entry.getKey());
                if (api.getGroup(groupId) != null) continue;

                String[] parts = entry.getValue().split("\0", 4);
                if (parts.length < 4) {
                    CrossVoiceChat.LOGGER.warn("Skipping malformed group entry: {} -> {}", entry.getKey(), entry.getValue());
                    continue;
                }
                if (parts[3].equals(serverName)) continue;

                String password = parts[1].isEmpty() ? null : new String(java.util.Base64.getDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
                createMirrorGroup(groupId, parts[0], Boolean.parseBoolean(parts[2]), password);

                Map<String, String> members = jedis.hgetAll(MEMBERS_PREFIX + groupId);
                for (Map.Entry<String, String> member : members.entrySet()) {
                    String memberServer = extractServer(member.getValue());
                    if (memberServer.equals(serverName)) continue;
                    UUID memberUuid = UUID.fromString(member.getKey());
                    String memberName = extractPlayerName(member.getValue());
                    injectRemotePlayerState(memberUuid, memberName, groupId);
                }
            }
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to load existing voice groups from Redis", e);
        }
    }

    public void onLocalGroupCreated(Group group) {
        if (creatingMirror.contains(group.getId())) return;
        locallyCreatedGroups.add(group.getId());
        groupNames.put(group.getId(), group.getName());
        String password = extractPassword(group);
        String encodedPassword = password != null ? java.util.Base64.getEncoder().encodeToString(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)) : "";
        try (Jedis jedis = jedisPool.getResource()) {
            String value = group.getName() + "\0"
                    + encodedPassword + "\0"
                    + group.isPersistent() + "\0"
                    + serverName;
            jedis.hset(GROUPS_KEY, group.getId().toString(), value);
            String msg = "create:" + group.getId() + ":" + group.getName()
                    + ":" + encodedPassword
                    + ":" + group.isPersistent()
                    + ":" + serverName;
            jedis.publish(CHANNEL, msg);
            CrossVoiceChat.LOGGER.info("Published group creation: '{}' ({}) hasPassword={}", group.getName(), group.getId(), password != null);
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to publish group creation", e);
        }
    }

    private static String extractPassword(Group group) {
        try {
            Object internal = group;
            // The API Group wraps the internal server Group
            for (var field : group.getClass().getDeclaredFields()) {
                if (field.getType().getName().equals("de.maxhenkel.voicechat.voice.server.Group")) {
                    field.setAccessible(true);
                    internal = field.get(group);
                    break;
                }
            }
            var method = internal.getClass().getMethod("getPassword");
            return (String) method.invoke(internal);
        } catch (Exception e) {
            return null;
        }
    }

    public void onLocalGroupRemoved(UUID groupId) {
        if (!locallyCreatedGroups.contains(groupId)) return;

        String name = groupNames.getOrDefault(groupId, "?");

        ScheduledFuture<?> existing = pendingRemovals.remove(groupId);
        if (existing != null) existing.cancel(false);

        Group recreated = api.groupBuilder()
                .setId(groupId)
                .setName(name)
                .setPersistent(true)
                .setType(Group.Type.NORMAL)
                .build();

        if (recreated == null) {
            CrossVoiceChat.LOGGER.warn("Failed to recreate group '{}' ({}) for grace period", name, groupId);
            locallyCreatedGroups.remove(groupId);
            groupNames.remove(groupId);
            removeGroupFromRedis(groupId);
            return;
        }

        CrossVoiceChat.LOGGER.info("Group '{}' ({}) emptied — recreated as persistent, {}s grace period",
                name, groupId, GROUP_GRACE_PERIOD_SECONDS);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pendingRemovals.remove(groupId);
            if (!getLocalMembersOfGroup(groupId).isEmpty()) {
                CrossVoiceChat.LOGGER.debug("Grace period expired for '{}' but has local members, keeping", name);
                return;
            }
            if (hasRemoteMembers(groupId)) {
                CrossVoiceChat.LOGGER.debug("Grace period expired for '{}' but has remote members, keeping", name);
                return;
            }

            CrossVoiceChat.LOGGER.debug("Voice group '{}' empty for {}s, removing", name, GROUP_GRACE_PERIOD_SECONDS);
            locallyCreatedGroups.remove(groupId);
            groupNames.remove(groupId);
            api.removeGroup(groupId);
            removeGroupFromRedis(groupId);
        }, GROUP_GRACE_PERIOD_SECONDS, TimeUnit.SECONDS);

        pendingRemovals.put(groupId, future);
    }

    private void removeGroupFromRedis(UUID groupId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hdel(GROUPS_KEY, groupId.toString());
            jedis.del(MEMBERS_PREFIX + groupId);
            jedis.publish(CHANNEL, "remove:" + groupId + ":" + serverName);
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to remove group from Redis", e);
        }
    }

    public void onLocalPlayerJoinedGroup(Group group, UUID playerUuid, String playerName) {
        if (syncInProgress.contains(playerUuid)) return;

        ScheduledFuture<?> pending = pendingRemovals.remove(group.getId());
        if (pending != null) {
            pending.cancel(false);
            CrossVoiceChat.LOGGER.debug("Cancelled pending removal for voice group '{}' — player joined", group.getName());
        }

        playerGroupMap.put(playerUuid, group.getId());
        remoteServersCache.remove(group.getId());
        CrossVoiceChat.LOGGER.debug("Player {} ({}) joined voice group '{}'", playerName, playerUuid, group.getName());
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(MEMBERS_PREFIX + group.getId(),
                    playerUuid.toString(), serverName + "\0" + playerName);
            jedis.publish(CHANNEL, "join:" + group.getId() + ":" + playerUuid
                    + ":" + playerName + ":" + serverName);
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to publish group join", e);
        }
    }

    public void onLocalPlayerLeftGroup(Group group, UUID playerUuid) {
        playerGroupMap.remove(playerUuid);
        remoteServersCache.remove(group.getId());
        CrossVoiceChat.LOGGER.debug("Player {} left voice group '{}'", playerUuid, group.getName());

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hdel(MEMBERS_PREFIX + group.getId(), playerUuid.toString());
            jedis.publish(CHANNEL, "leave:" + group.getId() + ":" + playerUuid + ":" + serverName);
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to publish group leave", e);
        }
    }

    public void onPlayerDisconnected(UUID playerUuid) {
        UUID groupId = playerGroupMap.remove(playerUuid);
        if (groupId == null) return;

        remoteServersCache.remove(groupId);

        String groupName = groupNames.getOrDefault(groupId, "Group");
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(REJOIN_PREFIX + playerUuid, REJOIN_TTL_SECONDS, groupId + ":" + groupName);
            jedis.hdel(MEMBERS_PREFIX + groupId, playerUuid.toString());
            jedis.publish(CHANNEL, "leave:" + groupId + ":" + playerUuid + ":" + serverName);
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to handle voice chat disconnect", e);
        }
        CrossVoiceChat.LOGGER.debug("Player {} voice chat disconnected, saved group {} ('{}') for rejoin ({}s TTL)",
                playerUuid, groupId, groupName, REJOIN_TTL_SECONDS);
    }

    public void syncAllGroupsToPlayer(VoicechatConnection connection) {
        if (connection == null || connection.getPlayer() == null) return;
        UUID playerUuid = connection.getPlayer().getUuid();
        String playerName = "Unknown";
        Object mcPlayer = connection.getPlayer().getPlayer();
        if (mcPlayer instanceof ServerPlayer sp) {
            playerName = sp.getGameProfile().getName();
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String rejoinData = jedis.get(REJOIN_PREFIX + playerUuid);
            if (rejoinData != null) {
                jedis.del(REJOIN_PREFIX + playerUuid);
                String[] rejoinParts = rejoinData.split(":", 2);
                UUID groupId = UUID.fromString(rejoinParts[0]);
                String groupName = rejoinParts.length > 1 ? rejoinParts[1] : "Group";

                api.removeGroup(groupId);
                Group group = api.groupBuilder()
                        .setId(groupId)
                        .setName(groupName)
                        .setPersistent(true)
                        .setType(Group.Type.NORMAL)
                        .build();
                if (group != null) {
                    UUID actualGroupId = group.getId();
                    syncInProgress.add(playerUuid);
                    connection.setGroup(group);
                    syncInProgress.remove(playerUuid);
                    playerGroupMap.put(playerUuid, actualGroupId);
                    groupNames.put(actualGroupId, groupName);
                    locallyCreatedGroups.add(actualGroupId);
                    jedis.hset(MEMBERS_PREFIX + actualGroupId,
                            playerUuid.toString(), serverName + "\0" + playerName);
                    String value = groupName + "\0" + "0" + "\0" + "true" + "\0" + serverName;
                    jedis.hset(GROUPS_KEY, actualGroupId.toString(), value);
                    jedis.publish(CHANNEL, "join:" + actualGroupId + ":" + playerUuid
                            + ":" + playerName + ":" + serverName);
                    CrossVoiceChat.LOGGER.info("Auto-rejoined player {} to voice group '{}' (id={}) after transfer",
                            playerUuid, groupName, actualGroupId);

                    Map<String, String> members = jedis.hgetAll(MEMBERS_PREFIX + actualGroupId);
                    CrossVoiceChat.LOGGER.info("Voice group '{}' has {} total members in Redis", groupName, members.size());
                    for (Map.Entry<String, String> member : members.entrySet()) {
                        String memberServer = extractServer(member.getValue());
                        if (memberServer.equals(serverName)) continue;
                        UUID memberUuid = UUID.fromString(member.getKey());
                        String memberName = extractPlayerName(member.getValue());
                        CrossVoiceChat.LOGGER.info("Injecting remote member {} ({}) from {} into group '{}'",
                                memberName, memberUuid, memberServer, groupName);
                        injectRemotePlayerState(memberUuid, memberName, actualGroupId);
                    }
                } else {
                    CrossVoiceChat.LOGGER.warn("Failed to create rejoin group '{}' ({}) for player {}",
                            groupName, groupId, playerUuid);
                }
            }
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to check rejoin group for {}", playerUuid, e);
        }
    }

    public boolean hasRemoteMembers(UUID groupId) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> members = jedis.hgetAll(MEMBERS_PREFIX + groupId);
            for (String value : members.values()) {
                if (!extractServer(value).equals(serverName)) return true;
            }
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to check remote members", e);
        }
        return false;
    }

    public Set<String> getRemoteServers(UUID groupId) {
        CachedServers cached = remoteServersCache.get(groupId);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.servers;
        }

        Set<String> servers = new HashSet<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> members = jedis.hgetAll(MEMBERS_PREFIX + groupId);
            for (String value : members.values()) {
                String server = extractServer(value);
                if (!server.equals(serverName)) servers.add(server);
            }
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to get remote servers for group", e);
        }
        remoteServersCache.put(groupId, new CachedServers(servers));
        return servers;
    }

    public Set<UUID> getRemoteMembersOnServer(UUID groupId, String targetServer) {
        Set<UUID> members = new HashSet<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> all = jedis.hgetAll(MEMBERS_PREFIX + groupId);
            for (Map.Entry<String, String> entry : all.entrySet()) {
                if (extractServer(entry.getValue()).equals(targetServer)) {
                    members.add(UUID.fromString(entry.getKey()));
                }
            }
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to get remote members", e);
        }
        return members;
    }

    public UUID getPlayerGroup(UUID playerUuid) {
        return playerGroupMap.get(playerUuid);
    }

    public Set<UUID> getLocalMembersOfGroup(UUID groupId) {
        Set<UUID> members = new HashSet<>();
        for (Map.Entry<UUID, UUID> entry : playerGroupMap.entrySet()) {
            if (entry.getValue().equals(groupId)) {
                members.add(entry.getKey());
            }
        }
        return members;
    }

    private void createMirrorGroup(UUID id, String name, boolean persistent) {
        createMirrorGroup(id, name, persistent, null);
    }

    private void createMirrorGroup(UUID id, String name, boolean persistent, String password) {
        try {
            Group existing = api.getGroup(id);
            if (existing != null && existing.getName() != null) {
                CrossVoiceChat.LOGGER.info("Mirror group '{}' ({}) already exists locally as '{}', skipping", name, id, existing.getName());
                return;
            }
        } catch (Exception ignored) {
        }
        creatingMirror.add(id);
        var builder = api.groupBuilder()
                .setId(id)
                .setName(name)
                .setPersistent(true)
                .setType(Group.Type.NORMAL);
        if (password != null && !password.isEmpty()) {
            builder.setPassword(password);
        }
        Group group = builder.build();
        creatingMirror.remove(id);
        if (group != null) {
            mirrorGroups.add(id);
            CrossVoiceChat.LOGGER.info("Created mirror voice group: '{}' ({}) hasPassword={}", name, id, password != null && !password.isEmpty());
        } else {
            CrossVoiceChat.LOGGER.warn("Failed to create mirror voice group: '{}' ({})", name, id);
        }
    }

    private void handleMessage(String message) {
        String[] parts = message.split(":", -1);
        if (parts.length < 3) return;
        String action = parts[0];
        String origin = parts[parts.length - 1];

        if (origin.equals(serverName)) return;

        CrossVoiceChat.LOGGER.info("Voice group sync message from {}: {}", origin, message);

        switch (action) {
            case "create" -> {
                if (parts.length >= 6) {
                    UUID groupId = UUID.fromString(parts[1]);
                    String name = parts[2];
                    String password = parts[3].isEmpty() ? null : new String(java.util.Base64.getDecoder().decode(parts[3]), java.nio.charset.StandardCharsets.UTF_8);
                    boolean persistent = Boolean.parseBoolean(parts[4]);
                    createMirrorGroup(groupId, name, persistent, password);
                } else {
                    CrossVoiceChat.LOGGER.warn("Malformed create message (expected 6+ parts, got {}): {}", parts.length, message);
                }
            }
            case "remove" -> {
                UUID groupId = UUID.fromString(parts[1]);
                if (mirrorGroups.remove(groupId)) {
                    api.removeGroup(groupId);
                    CrossVoiceChat.LOGGER.debug("Removed mirror voice group: {}", groupId);
                }
            }
            case "join" -> {
                if (parts.length >= 5) {
                    UUID gid = UUID.fromString(parts[1]);
                    UUID playerUuid = UUID.fromString(parts[2]);
                    String playerName = parts[3];
                    remoteServersCache.remove(gid);
                    ScheduledFuture<?> pending = pendingRemovals.remove(gid);
                    if (pending != null) {
                        pending.cancel(false);
                        CrossVoiceChat.LOGGER.debug("Cancelled pending removal for group {} — remote player joined", gid);
                    }
                    CrossVoiceChat.LOGGER.debug("Remote join: {} ({}) joined group {} from {}, injecting state",
                            playerName, playerUuid, gid, origin);
                    injectRemotePlayerState(playerUuid, playerName, gid);
                } else {
                    UUID gid = UUID.fromString(parts[1]);
                    remoteServersCache.remove(gid);
                    ScheduledFuture<?> pending = pendingRemovals.remove(gid);
                    if (pending != null) pending.cancel(false);
                }
            }
            case "leave" -> {
                UUID gid = UUID.fromString(parts[1]);
                remoteServersCache.remove(gid);
                if (parts.length >= 4) {
                    UUID playerUuid = UUID.fromString(parts[2]);
                    removeRemotePlayerState(playerUuid);
                }
            }
        }
    }

    private void injectRemotePlayerState(UUID playerUuid, String playerName, UUID groupId) {
        try {
            var serverEvents = Voicechat.SERVER;
            if (serverEvents == null) return;
            var vcServer = serverEvents.getServer();
            if (vcServer == null) return;

            if (vcServer.getConnection(playerUuid) != null) return;

            var stateManager = vcServer.getPlayerStateManager();

            ConcurrentHashMap<UUID, PlayerState> states = getStatesMap(stateManager);
            if (states == null) return;

            PlayerState remoteState = new PlayerState(playerUuid, playerName, false, false);
            remoteState.setGroup(groupId);
            states.put(playerUuid, remoteState);
            remotePlayerStates.add(playerUuid);

            var mcServer = vcServer.getServer();
            mcServer.execute(() -> {
                for (ServerPlayer player : mcServer.getPlayerList().getPlayers()) {
                    NetManager.sendToClient(player, new PlayerStatePacket(remoteState));
                }
            });

            CrossVoiceChat.LOGGER.debug("Injected remote player state: {} ({}) in group {}", playerName, playerUuid, groupId);
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to inject remote player state for {}", playerUuid, e);
        }
    }

    private void removeRemotePlayerState(UUID playerUuid) {
        if (!remotePlayerStates.remove(playerUuid)) return;
        removeRemotePlayerStateInternal(playerUuid);
    }

    private void removeRemotePlayerStateInternal(UUID playerUuid) {
        try {
            var serverEvents = Voicechat.SERVER;
            if (serverEvents == null) return;
            var vcServer = serverEvents.getServer();
            if (vcServer == null) return;

            if (vcServer.getConnection(playerUuid) != null) return;

            var stateManager = vcServer.getPlayerStateManager();
            ConcurrentHashMap<UUID, PlayerState> states = getStatesMap(stateManager);
            if (states != null) {
                states.remove(playerUuid);
            }

            var mcServer = vcServer.getServer();
            mcServer.execute(() -> {
                for (ServerPlayer player : mcServer.getPlayerList().getPlayers()) {
                    NetManager.sendToClient(player, new RemovePlayerStatePacket(playerUuid));
                }
            });

            CrossVoiceChat.LOGGER.debug("Removed remote player state: {}", playerUuid);
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to remove remote player state for {}", playerUuid, e);
        }
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<UUID, PlayerState> getStatesMap(PlayerStateManager stateManager) throws Exception {
        if (statesField == null) {
            statesField = PlayerStateManager.class.getDeclaredField("states");
            statesField.setAccessible(true);
        }
        return (ConcurrentHashMap<UUID, PlayerState>) statesField.get(stateManager);
    }

    private String extractServer(String memberValue) {
        int idx = memberValue.indexOf('\0');
        return idx >= 0 ? memberValue.substring(0, idx) : memberValue;
    }

    private String extractPlayerName(String memberValue) {
        int idx = memberValue.indexOf('\0');
        return idx >= 0 ? memberValue.substring(idx + 1) : "Unknown";
    }

    private static class CachedServers {
        final Set<String> servers;
        final long timestamp;

        CachedServers(Set<String> servers) {
            this.servers = servers;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private void subscribeLoop() {
        while (running) {
            try (Jedis jedis = jedisPool.getResource()) {
                CrossVoiceChat.LOGGER.info("Voice group Redis subscription started on channel: {}", CHANNEL);
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        try {
                            handleMessage(message);
                        } catch (Exception e) {
                            CrossVoiceChat.LOGGER.warn("Error handling voice group message: {}", message, e);
                        }
                    }
                }, CHANNEL);
            } catch (Exception e) {
                if (running) {
                    CrossVoiceChat.LOGGER.warn("Voice group Redis subscription lost, reconnecting in 5s", e);
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) { break; }
                }
            }
        }
    }
}
