# ServerFlashback

**English** | [简体中文](README-CN.md)

Server-side Fabric mod that records Minecraft replays in FlashBack's native format. Supports area-based recording with extended chunk range beyond the server's loaded chunks. Output files are directly compatible with the [FlashBack](https://github.com/Flashback-MC/Flashback) client mod for playback.

## Supported Versions

- Minecraft 1.17.1
- Minecraft 1.21.4

## Requirements

- Fabric Loader
- Fabric API
- Server-side only (no client installation required for recording)

## Installation

1. Download the build for your Minecraft version from [Releases](https://github.com/Trirrin/ServerFlashback/releases)
2. Place the JAR in your server's `mods` folder
3. Restart the server

## Commands

All commands require OP level 2:

| Command | Description |
|---------|-------------|
| `/serverflashback start <pos> <radius> [name]` | Start recording at block position with given radius (16–4096 blocks) |
| `/serverflashback stop [name]` | Stop recording. Omit name to stop the only active recording |
| `/serverflashback pause [name]` | Pause recording |
| `/serverflashback resume [name]` | Resume paused recording |
| `/serverflashback list` | List active recordings |
| `/serverflashback mark <name> [description]` | Add a marker to the current recording (run as player) |

## Output

Replay files (`.zip`) are saved to `<server_root>/serverflashback/replays/`. Open them with the FlashBack client mod for playback.

## Build

```bash
./gradlew :1.21.4:build              # Build for MC 1.21.4
./gradlew :1.17.1:build --no-parallel # Build for MC 1.17.1
./gradlew :1.21.4:compileJava        # Compile only (faster)
```

## License

GPL-3.0
