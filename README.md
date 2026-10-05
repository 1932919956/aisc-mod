# Agnes AI Partner

Minecraft 1.20.1 Forge mod that adds an Agnes-controlled survival layer for Touhou Little Maid.

## Features

- Autonomous survival planning with configurable request interval.
- Gathering, crafting, equipment, storage, hunting and safe staircase mining.
- Maid vision/state context, durable action history and remembered landmarks.
- Optional Zhipu fallback and any OpenAI-compatible fallback endpoint.
- Promaid cooperation, native maid bubbles, rescue, blueprints and board games.

## Requirements

- Minecraft 1.20.1
- Forge 47.4.18 (47.x is supported)
- Java 17
- Touhou Little Maid 1.5.3 for Minecraft 1.20.1

Promaid 1.3.0 is optional. It is distributed separately because it is a third-party mod.

## Build

1. Put the Touhou Little Maid dependency JAR at `libs/touhoulittlemaid-1.5.3.jar`.
2. Use Java 17 and run `gradlew build`.
3. For the portable release, run `gradlew -Pportable build`.

The output JAR is written to `build/libs/`. Verification harnesses are opt-in and are not included in a normal release build.

## Configuration and API keys

The repository contains no personal API keys. Defaults are intentionally blank. The mod creates `config/agnespartner-portable.toml` in the game directory. Enter the key in the in-game Mods configuration screen, or edit `apiKey = ""` after the first launch.

Optional fallback fields are `zhipuApiKey`, `zhipuModel`, `customApiUrl`, `customApiKey`, and `customModel`. Never commit a configured game `config` directory, logs, screenshots or cache files.

## License

MIT. See `LICENSE`.
