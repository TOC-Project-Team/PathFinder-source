# PathFinder Plugin Documentation

> Latest supported server version: **26.1**  
> Function: player navigation, waypoint management, and particle-based route guidance  
> Build: Java 21 + Gradle + Shadow fat jar packaging

---

## Plugin Introduction

**PathFinder** is a Paper/Spigot plugin focused on navigation and waypoint workflows for Minecraft servers. It uses Java-based path guidance, asynchronous runtime tasks, and player-visible particle routes to guide users toward targets without blocking the main server thread.

Key points:

- Asynchronous path processing designed to avoid main-thread lag.
- Real-time particle path guidance visible to the navigating player.
- Waypoint creation, editing, listing, and navigation through `/toc nav`.
- GUI entry points for player navigation and admin controls.
- Multi-language support with bundled language files.
- Configurable pathfinding cost, particle, and search-radius settings.

---

## Plugin Demonstration

Navigation effect:

![Navigation Demo 1](https://free.picui.cn/free/2025/08/29/68b19dcf4573e.png)
![Navigation Demo 2](https://free.picui.cn/free/2025/08/29/68b19dd0beeb5.png)
![Navigation Demo 3](https://free.picui.cn/free/2025/08/29/68b19dd0703bf.png)
![Navigation Demo 4](https://free.picui.cn/free/2025/08/29/68b19dd19ee3c.png)
![Navigation Demo 5](https://free.picui.cn/free/2025/08/29/68b19dd2d5f75.png)
![Navigation Demo 6](https://free.picui.cn/free/2025/08/29/68b19dd62a476.png)
![Navigation Demo 7](https://free.picui.cn/free/2025/08/29/68b19dd91589d.png)
![Navigation Demo 8](https://free.picui.cn/free/2025/08/29/68b19dda29a0c.png)
![Navigation Demo 9](https://free.picui.cn/free/2025/08/29/68b19ddb243f3.png)
![Navigation Demo 10](https://free.picui.cn/free/2025/08/29/68b19ddb872a3.png)

`/toc cd` opens the player navigation page:

![Player Navigation GUI](https://free.picui.cn/free/2025/08/29/68b19ddba00d2.png)

`/toc admin` opens the admin menu:

![Admin GUI](https://free.picui.cn/free/2025/08/29/68b19dde37604.png)

---

## Commands And Permissions Quick Reference

| Command | Permission Node | Description |
| --- | --- | --- |
| `/toc admin` | `toc.admin` | Open the admin menu |
| `/toc reload` | `toc.admin` | Reload plugin configuration |
| `/toc status` | `toc.admin` | View plugin status information |
| `/toc cd` | `toc.cd` | Open the player navigation menu |
| `/toc lang <language\|reset>` | `toc.lang` | Change or reset the player's language |
| `/toc nav add <name> <x> <y> <z> [world]` | `toc.nav.add` | Create a waypoint |
| `/toc nav remove <name>` | `toc.nav.remove` | Delete a waypoint |
| `/toc nav rename <old_name> <new_name>` | `toc.nav.rename` | Rename a waypoint |
| `/toc nav set <name> <x\|y\|z\|world> <value>` | `toc.nav.set` | Modify a waypoint field |
| `/toc nav start <player> <name>` | `toc.nav.start` | Force a player to start navigation |
| `/toc nav go <name>` | `toc.nav.go` | Navigate to a saved waypoint |
| `/toc nav stop` | `toc.nav.stop` | Stop your own navigation |
| `/toc nav stop <player>` | `toc.nav.stop.other` | Stop another player's navigation |
| `/toc nav list [--page=] [--world=]` | `toc.nav.list` | List saved waypoints |
| `/toc nav view [--page=]` | `toc.view` | View active navigation sessions |

---

## Configuration File Details

### `config.yml`

Main plugin configuration:

```yaml
language: "en-US"
allow_navigation_to_invisible: false
```

Configuration notes:

- `language` sets the default plugin language.
- `allow_navigation_to_invisible` controls whether invisible target players can still be navigated to.

### `pathfinder.yml`

Pathfinding configuration:

```yaml
max_search_radius: 3000
max_iterations: 4000
particle_spacing: 0.5
max_particle_distance: 30
particle_size: 1.0
path_refresh_ticks: 15
diagonal_cost: 1.5
straight_cost: 1.0
right_angle_turn_cost: 0.5
diagonal_turn_cost: 1.0
break_block_cost: 100.0
door_cost: 0.0
trapdoor_cost: 6.0
jump_cost: 0.0
vertical_cost: 1.0
scaffolding_cost: 0.0
fall_cost: 2.0
block_jump_cost: 1.0
max_block_jump_distance: 4
max_safe_fall_height: 4
enable_path_caching: false
```

Tuning notes:

- Larger `max_search_radius` increases range but also increases processing cost.
- Larger `max_iterations` improves accuracy at a higher CPU and memory cost.
- Smaller `particle_spacing` creates denser particle lines.
- Larger `max_particle_distance` increases client-facing visibility and bandwidth usage.
- Setting `enable_path_caching` to `true` reduces recomputation but can make routes less reactive.

### Low-Spec Server Optimization Example

```yaml
max_search_radius: 200
max_iterations: 1000
path_refresh_ticks: 30
```

Configuration changes are intended to be lightweight to maintain, and lower values are more suitable for smaller servers.

### Notes

- This plugin is better suited to general navigation than high-precision parkour routing.
- Vertical path support is primarily designed around ladders and scaffolding.
- Underwater pathfinding can require additional testing depending on your map design.

---

## Build

### Full Fat Jar Build

```bash
./scripts/build-fatjar.sh
```

This script:

- attempts to auto-detect `JAVA_HOME`
- cleans stray `.class` files outside Gradle output directories
- runs `./gradlew clean shadowJar`
- verifies the generated fat jar and reports its size and class count

### Build Artifact

The main artifact produced by `./scripts/build-fatjar.sh` is written to:

```text
.gradle-build/libs/PathFinder-1.6.0-all.jar
```

The script resolves the final file from `.gradle-build/libs/*-all.jar`, so the exact filename follows the version declared in `build.gradle`.

If `RELEASE_COPY=1` is provided, the script also copies a timestamped build artifact into:

```text
release/
```

Example:

```bash
RELEASE_COPY=1 ./scripts/build-fatjar.sh
```

### Fast Build

```bash
./scripts/quick-build.sh
```

This is a faster build path that skips the full clean step and runs `shadowJar` directly.

### Legacy Entry Point

```bash
./build.sh
```

---

## Project Layout

```text
.
├── build.gradle
├── gradle/
├── scripts/
│   ├── build-fatjar.sh
│   └── quick-build.sh
├── src/main/java/
└── src/main/resources/
```

---

## Language Support

Bundled language files currently include:

- `zh-CN`
- `zh-TW`
- `en-US`
- `de-DE`
- `ru-RU`
- `es-ES`
- `pt-PT`
- `fr-FR`

Language files live under `plugins/PathFinder/lang/` at runtime and can be extended as needed. Language filenames should follow RFC 1766 style identifiers such as `en-US` or `zh-CN`.

---

## Official Support

Discord community: [https://discord.gg/daSchNY7Sr](https://discord.gg/daSchNY7Sr)

Enjoy using PathFinder.
