# Cross-Server Simple Voice Chat

A server-side NeoForge addon for [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) that makes **voice groups work across multiple backend servers** behind a proxy (Velocity). Two players in the same voice group can talk to each other even when they are on different servers of your network.

Proximity voice stays server-local, exactly as SVC intends. Only group audio crosses servers.

This addon was extracted from the [Gearworks](https://www.gwsmp.com) server network, where it has been running in production across three backend servers.

## How it works

- **Group sync (Redis):** When a group is created, joined, or left on one server, the event is published over Redis pub/sub. Every other server creates a *mirror group* with the same UUID and name, so SVC's stock client UI shows the group everywhere with no client mod needed beyond SVC itself.
- **Member visibility:** Remote group members are injected into each server's SVC player state, so the client shows who is in the group across the whole network.
- **Audio relay (UDP):** When someone in a group speaks, the opus-encoded audio is sent directly between the backend servers over a lightweight UDP protocol (one hop, no Redis in the audio path). The receiving server plays it to its local group members through SVC.
- **Transfer-aware rejoin:** When a player disconnects, their group is remembered in Redis for 30 seconds. If they reappear on another server within that window (a proxy transfer), they rejoin their group automatically.
- **Grace period:** A group whose last member leaves sticks around for 30 seconds before being removed, so brief disconnects and server hops don't kill the group.

Requirements: a shared **Redis** instance, and backend servers that can reach each other over **UDP** (same Docker network, Kubernetes cluster, or LAN).

## Installation

1. Install [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) on every backend server, and the [SVC Velocity plugin](https://modrinth.com/plugin/simple-voice-chat/versions?l=velocity) on your proxy (voice does not work through a proxy without it).
2. Drop the `cross-server-simple-voice-chat` jar into `mods/` on every backend server. It is server-side only; clients need nothing beyond SVC.
3. Configure each server (see below). At minimum every server needs a unique `server.name` and the shared Redis address.

## Configuration

The config file is created at `world/serverconfig/crossvoicechat-server.toml`. Every value can also be set through an environment variable, which takes precedence — convenient for Docker and Kubernetes where all servers share one image:

| Config | Env var | Default | Meaning |
| --- | --- | --- | --- |
| `server.name` | `SERVER_NAME` | `server1` | Unique name of this backend server |
| `redis.host` | `REDIS_HOST` | `localhost` | Shared Redis host |
| `redis.port` | `REDIS_PORT` | `6379` | Redis port |
| `redis.password` | `REDIS_PASSWORD` | *(empty)* | Redis password, empty for no auth |
| `redis.prefix` | `REDIS_PREFIX` | `crossvoice:` | Prefix for all Redis keys/channels |
| `relay.port` | `VOICE_RELAY_PORT` | `0` (auto) | UDP port for the audio relay |
| `relay.host` | `VOICE_RELAY_HOST` | *(auto-detect)* | Hostname other servers reach this one on |

`relay.host` matters: it is the address the *other* servers send audio to. Inside Docker/Kubernetes, set it to the service or container name. If unset, the machine's own hostname is used, which is usually right in containerized setups.

## Try it with Docker

The `docker/` directory contains a complete demo network: Redis, two NeoForge servers with SVC and this addon, and a Velocity proxy with the SVC plugin.

```sh
./gradlew shadowJar
cd docker
./setup.sh          # copies the built jar, downloads the SVC Velocity plugin
docker compose up
```

Connect a Minecraft 1.21.1 NeoForge client (with Simple Voice Chat installed) to `localhost:25565` with two accounts. Then:

1. Create a voice group on one account (`V` key, "Create group").
2. Send the other account to the second server: `/server server2`.
3. Join the same group from there — it shows up automatically.
4. Talk. Audio flows across the two servers.

The demo proxy runs with `player-info-forwarding-mode = "NONE"` to keep the backends stock. A production network should use modern forwarding with a forwarding mod on the backends (for example NeoForwarding).

## Building

```sh
./gradlew shadowJar
```

The jar lands in `build/libs/`. Java 21 required. The build compiles against the full Simple Voice Chat jar from the Modrinth maven — no manual jar downloads needed.

## Caveats

- Uses a few of SVC's internal classes (player state injection, group password reading, audio packet dispatch) beyond the published API, so new SVC versions can occasionally break it. Compiled and tested against SVC `2.6.x` on Minecraft 1.21.1.
- Group passwords are synced between servers through Redis (base64, not encrypted). Use Redis auth and a trusted network.
- Whisper state is carried in the relay packet but currently ignored on the receiving side.

## License

[MIT](LICENSE)
