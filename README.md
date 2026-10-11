# NexusCore-Spigot

Spigot plugin that connects a Minecraft server to [NexusCore](https://github.com/mustafabinguldev/nexuscore),
the Redis-backed data and cache core. Other plugins use it to read and write NexusCore data,
and servers use it to send messages to each other.

- **Durable requests**: every request goes onto NexusCore's Redis Stream (`XADD`), so nothing is lost while NexusCore restarts.
- **Signed packets**: HMAC-SHA256 signatures, timestamps and nonces, compatible with NexusCore's validators.
- **Watchdog**: tracks NexusCore's heartbeat. While NexusCore is down, writes are queued and sent in order once it is back. Logins can be blocked and players kicked so nobody plays on stale data.
- **Typed data API**: annotate a model class and use `getData`, `setData`, `increment`, `remove`, `ranking` and more.
- **Server-to-server channels**: typed Pub/Sub messages between your servers.
- Shaded and relocated Jedis + Jackson, so they don't conflict with other plugins.

Requires Spigot (or a fork) **1.13+** on **Java 17+**, Redis, and a running NexusCore.

## Installation

1. Download the jar from the [releases](https://github.com/mustafabinguldev/NexusCore-Spigot/releases) or build it yourself (`mvn package`).
2. Put it into `plugins/` and start the server once.
3. Edit `plugins/NexusCore-Spigot/config.yml`:
   - `server-name`: a **unique** name per server. NexusCore answers on `<response-channel-prefix><server-name>`.
   - `redis.*`: the Redis instance NexusCore uses.
   - `security.signing-key`: the same key as `NEXUS_SIGNING_KEY` on NexusCore (or set the `NEXUS_SIGNING_KEY` environment variable).
4. Restart and check the connection with `/nexusbridge status`.

> If NexusCore runs without a signing key (`NEXUS_ALLOW_UNSIGNED_MESSAGES=true`), set
> `security.allow-unsigned-messages: true` as well. Use this only for development.

## Using the API

Add the dependency (GitHub Packages) with scope `provided`:

```xml
<repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/mustafabinguldev/NexusCore-Spigot</url>
</repository>

<dependency>
    <groupId>io.github.mustafabinguldev</groupId>
    <artifactId>nexuscore-spigot</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

and declare it in your `plugin.yml`:

```yaml
depend: [ NexusCore-Spigot ]
```

### Data addons

The protocol id and field names must match the addon (or JSON model) on the NexusCore side.

```java
public class PlayerStats {
    @NexusField(id = true)
    private String uuid;

    @NexusField(defaultValue = "0")
    private int kills;

    @NexusField(defaultValue = "1000")
    private double coins;

    @NexusField(defaultValue = "[]")
    private List<String> unlockedKits;

    // getters/setters omitted
}

public class PlayerStatsAddon extends NexusAddon<PlayerStats> {
    @Override public int protocolId() { return 10; }
    @Override public String name() { return "player-stats"; }
    @Override public Class<PlayerStats> dataClass() { return PlayerStats.class; }

    @Override
    protected void onDataReceived(PlayerStats stats) {
        // every model NexusCore sends to this server ends up here
    }
}
```

```java
PlayerStatsAddon stats = new PlayerStatsAddon();
NexusSpigot.bridge().registerAddon(stats);

// Read (NexusCore creates the entry with defaults if it doesn't exist yet)
stats.getData(player.getUniqueId().toString())
     .thenAccept(data -> player.sendMessage("Coins: " + data.getCoins()))
     .exceptionally(error -> { getLogger().warning("No answer: " + error); return null; });

// Write
stats.setData(model);                                // create or replace
stats.increment(uuid, "kills", 1);                   // atomic add on NexusCore
stats.remove(uuid, false);                           // drop from cache (true = also from database)
stats.loadCache(uuid);                               // warm the cache, e.g. on pre-login

// Leaderboards
stats.ranking("kills", SortOrder.DESC, 10).thenAccept(top -> ...);   // Map<position, value>
stats.rankPosition(uuid, "kills", SortOrder.DESC).thenAccept(pos -> ...);
```

With `main-thread-callbacks: true` (default) futures complete and listeners run on the main thread,
so you can use the Bukkit API directly. **Never block the main thread with `join()`/`get()`.** The answer
is delivered on the main thread too, so blocking would wait until the request times out.

Writes (`setData`, `increment`, `remove`, `loadCache`) made while NexusCore is unreachable are queued
(`requests.offline-queue-size`) and sent in order when the connection is back. Reads fail with a
`TimeoutException` after `requests.timeout-ms`.

### Server-to-server channels

```java
@MessageTag("staff-chat")
public class StaffChatMessage extends ChannelMessage {
    @MessageField("sender") public String sender;
    @MessageField("text")   public String text;
}

NexusChannel channel = NexusSpigot.bridge().channel("my_network");
channel.register(StaffChatMessage.class, msg ->
        Bukkit.broadcast("[" + msg.source + "] " + msg.sender + ": " + msg.text, "staff.chat"));

StaffChatMessage message = new StaffChatMessage();
message.sender = player.getName();
message.text = "Hello";
channel.publish(message);
```

Channel messages are signed like NexusCore packets. Use `channel.receiveOwnMessages(false)` to skip
messages published by the same server.

### Connection events

| Event | When |
|---|---|
| `NexusConnectionLostEvent` | No heartbeat within `watchdog.timeout-ms`. `setKickPlayers(false)` keeps players online. |
| `NexusConnectionRestoredEvent` | Heartbeats are back. Queued writes are sent right after. |

## Protocol

| Direction | Transport | Key / channel |
|---|---|---|
| Server -> NexusCore | Redis Stream (`XADD`), field `payload` | `nexus.stream-key` |
| NexusCore -> Server | Pub/Sub | `nexus.response-channel-prefix` + `server-name` |
| Heartbeat (every second) | Pub/Sub | `nexus.live-channel` |
| Server <-> Server | Pub/Sub | any channel name you choose |

Request types: `GET_DATA`, `SET_DATA`, `INCREMENT_DATA`, `REMOVE_DATA`, `LOAD_CACHE`, `RANKING`, `RANK_FINDER`.
Response types: `BROADCAST`, `RANKING_RESPONSE`, `RANK_FINDER_RESPONSE`.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/nexusbridge status` | `nexusbridge.admin` | Connection state, heartbeat, queued writes, addons, channels |

## Building

```bash
mvn package
```

The plugin jar is `target/nexuscore-spigot-<version>.jar`.

## License

[MIT](LICENSE)