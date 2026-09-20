### 📦 Version 0.0.3

- **Fixed** Teleport cooldown was calculated by subtracting milliseconds from a seconds value, so it expired almost instantly instead of respecting `teleportCooldownSeconds`.
- **Fixed** Cooldown was cleared on logout, letting players bypass it by relogging; it's now stored persistently and survives restarts.
- **Fixed** Requesting `/sablertp` while a teleport was already pending started a second search instead of being rejected.
- **Fixed** Ground vehicles and airships could land partially inside the terrain, since the destination Y wasn't adjusted for the contraption's offset.
- **Fixed** `groundClearance` config option was defined but never actually used by the safety check.
- **Fixed** Running `/sablertp` from console or a command block threw an error instead of a proper message.
- **Fixed** Safety checks identified trees and plants by reading block display names, which broke on non-English servers and modded blocks; now uses block tags.
- **Fixed** Negative X/Z coordinates were truncated incorrectly, causing off-by-one errors when searching west/north of the origin.
- **Fixed** Config values were cached at startup, so editing the config or reloading had no effect until a restart.
- **Fixed** Only tracked players were moved with the ship; mobs, pets, and other entities on board could be left behind.
- **Added** Destination search now runs asynchronously across ticks instead of blocking the server thread, removing the freeze that could occur when teleporting into ungenerated terrain.
- **Added** `minTeleportRadius` config option to prevent teleporting too close to the starting location.
- **Added** Configurable search origin (world origin, world spawn, or current position).
- **Added** Per-dimension blacklist to disable `/sablertp` in specific dimensions.
- **Added** `/sablertp cancel` to cancel a pending search or warmup.
- **Added** Teleport warmup now counts down in server ticks rather than wall-clock time, and all messages are localized.
- **Changed** All messages now use translation keys instead of hardcoded text, for full lang file support.