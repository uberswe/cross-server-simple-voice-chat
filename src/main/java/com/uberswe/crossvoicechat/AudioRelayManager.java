package com.uberswe.crossvoicechat;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.voice.common.GroupSoundPacket;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class AudioRelayManager {

    private final String RELAY_REGISTRY_KEY;

    private final GroupSyncManager groupSync;
    private final String serverName;
    private DatagramSocket socket;
    private Thread receiveThread;
    private volatile boolean running;
    private JedisPool jedisPool;

    private final Map<String, InetSocketAddress> peerAddresses = new ConcurrentHashMap<>();
    private final AtomicLong packetCounter = new AtomicLong(0);

    private final Map<UUID, AtomicLong> senderSequences = new ConcurrentHashMap<>();

    public AudioRelayManager(GroupSyncManager groupSync) {
        this.groupSync = groupSync;
        this.serverName = Config.getServerName();
        this.RELAY_REGISTRY_KEY = Config.getRedisPrefix() + "voicechat:relay_addrs";
    }

    public void start() throws SocketException {
        int port = Config.getVoiceRelayPort();
        socket = (port > 0) ? new DatagramSocket(port) : new DatagramSocket();
        socket.setSoTimeout(1000);

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        jedisPool = new JedisPool(poolConfig,
                Config.getRedisHost(),
                Config.getRedisPort(),
                2000,
                Config.getRedisPassword());

        registerRelayAddress();
        loadPeerAddresses();

        running = true;
        receiveThread = new Thread(this::receiveLoop, "CrossVoice-AudioRelay");
        receiveThread.setDaemon(true);
        receiveThread.start();

        CrossVoiceChat.LOGGER.info("Voice relay UDP socket bound to port {}", socket.getLocalPort());
    }

    public void stop() {
        running = false;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hdel(RELAY_REGISTRY_KEY, serverName);
        } catch (Exception ignored) {}

        senderSequences.clear();

        if (socket != null && !socket.isClosed()) socket.close();
        if (jedisPool != null) jedisPool.close();
    }

    private void registerRelayAddress() {
        try (Jedis jedis = jedisPool.getResource()) {
            String hostname = getContainerHostname();
            String addr = hostname + ":" + socket.getLocalPort();
            jedis.hset(RELAY_REGISTRY_KEY, serverName, addr);
            CrossVoiceChat.LOGGER.info("Registered voice relay address: {} -> {}", serverName, addr);
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.error("Failed to register relay address in Redis", e);
        }
    }

    private void loadPeerAddresses() {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> all = jedis.hgetAll(RELAY_REGISTRY_KEY);
            for (Map.Entry<String, String> entry : all.entrySet()) {
                if (entry.getKey().equals(serverName)) continue;
                parsePeerAddress(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to load peer relay addresses", e);
        }
    }

    private void parsePeerAddress(String server, String addrStr) {
        String[] parts = addrStr.split(":");
        if (parts.length != 2) return;
        try {
            InetAddress addr = InetAddress.getByName(parts[0]);
            int port = Integer.parseInt(parts[1]);
            peerAddresses.put(server, new InetSocketAddress(addr, port));
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Invalid peer address for {}: {}", server, addrStr, e);
        }
    }

    private String getContainerHostname() {
        String configured = Config.getVoiceRelayHost();
        if (configured != null) return configured;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Could not detect local hostname, falling back to server name '{}'. "
                    + "Set relay.host (or VOICE_RELAY_HOST) if other servers cannot reach this one.", serverName);
            return serverName;
        }
    }

    public void onVoicePacket(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null || !sender.isInGroup()) return;

        UUID groupId = sender.getGroup().getId();
        UUID senderUuid = sender.getPlayer().getUuid();
        byte[] opusData = event.getPacket().getOpusEncodedData();
        boolean whispering = event.getPacket().isWhispering();

        Set<String> remoteServers = groupSync.getRemoteServers(groupId);
        if (remoteServers.isEmpty()) return;

        long count = packetCounter.incrementAndGet();
        if (count % 100 == 0) loadPeerAddresses();
        if (count % 500 == 0) {
            CrossVoiceChat.LOGGER.info("Voice relay stats: sent {} packets, relaying {} -> {} servers for group {}",
                    count, senderUuid, remoteServers, groupId);
        }

        byte[] packet = buildRelayPacket(senderUuid, groupId, opusData, whispering);

        for (String targetServer : remoteServers) {
            InetSocketAddress addr = peerAddresses.get(targetServer);
            if (addr == null) {
                loadPeerAddresses();
                addr = peerAddresses.get(targetServer);
            }
            if (addr == null) continue;

            try {
                socket.send(new DatagramPacket(packet, packet.length, addr));
            } catch (Exception e) {
                CrossVoiceChat.LOGGER.debug("Failed to relay voice to {}: {}", targetServer, e.getMessage());
            }
        }
    }

    // Packet format: [magic 0xFE][senderUUID 16b][groupUUID 16b][whispering 1b][opus data...]
    private byte[] buildRelayPacket(UUID sender, UUID group, byte[] opus, boolean whispering) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 16 + 16 + 1 + opus.length);
        buf.put((byte) 0xFE);
        buf.putLong(sender.getMostSignificantBits());
        buf.putLong(sender.getLeastSignificantBits());
        buf.putLong(group.getMostSignificantBits());
        buf.putLong(group.getLeastSignificantBits());
        buf.put((byte) (whispering ? 1 : 0));
        buf.put(opus);
        return buf.array();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                DatagramPacket dgram = new DatagramPacket(buffer, buffer.length);
                socket.receive(dgram);
                processReceivedPacket(dgram.getData(), dgram.getLength());
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (running) {
                    CrossVoiceChat.LOGGER.debug("Voice relay receive error: {}", e.getMessage());
                }
            }
        }
    }

    private void processReceivedPacket(byte[] data, int length) {
        if (length < 34 || data[0] != (byte) 0xFE) return;

        ByteBuffer buf = ByteBuffer.wrap(data, 1, length - 1);
        UUID senderUuid = new UUID(buf.getLong(), buf.getLong());
        UUID groupId = new UUID(buf.getLong(), buf.getLong());
        @SuppressWarnings("unused")
        boolean whispering = buf.get() != 0;

        byte[] opusData = new byte[length - 34];
        buf.get(opusData);

        Set<UUID> localMembers = groupSync.getLocalMembersOfGroup(groupId);
        if (localMembers.isEmpty()) {
            CrossVoiceChat.LOGGER.debug("Received relay packet for group {} from {} but no local members", groupId, senderUuid);
            return;
        }

        long seq = senderSequences.computeIfAbsent(senderUuid, k -> new AtomicLong(0)).incrementAndGet();

        if (seq % 500 == 1) {
            CrossVoiceChat.LOGGER.info("Voice relay receiving from {} in group {}, forwarding to {} local members",
                    senderUuid, groupId, localMembers.size());
        }

        try {
            var serverEvents = Voicechat.SERVER;
            if (serverEvents == null) return;
            var vcServer = serverEvents.getServer();
            if (vcServer == null) return;

            GroupSoundPacket packet = new GroupSoundPacket(senderUuid, senderUuid, opusData, seq, null);

            for (UUID memberUuid : localMembers) {
                ClientConnection conn = vcServer.getConnection(memberUuid);
                if (conn == null) {
                    CrossVoiceChat.LOGGER.debug("No ClientConnection for local member {} in group {}", memberUuid, groupId);
                    continue;
                }
                vcServer.sendPacket(packet, conn);
            }
        } catch (Exception e) {
            CrossVoiceChat.LOGGER.warn("Failed to send group sound packet: {}", e.getMessage());
        }
    }
}
