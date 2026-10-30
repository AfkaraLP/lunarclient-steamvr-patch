# lunar-vr

Play **Minecraft 1.8.9 in Lunar Client** in a SteamVR headset with real stereoscopic 3D and
head tracking, using a normal keyboard and mouse. It's not a flat screen floating in VR:
each eye gets its own render with the headset's lens projection.

Built and tested on a **Steam Frame** streaming from Linux (NixOS) through SteamVR. Nothing
in it is specific to the Frame, so other SteamVR headsets should work too.

> **Experimental, unofficial.** This injects a Java agent into Lunar Client. It isn't
> affiliated with or endorsed by Lunar Client, Mojang or Valve. Use it at your own risk.

## Why this exists

[Vivecraft](https://vivecraft.org/) skips 1.8.x, and Lunar doesn't load Forge or Fabric mods
on 1.8.9. This project hooks Lunar's 1.8.9 renderer directly with a `-javaagent`.

## Features

- Per-eye stereo rendering. The world, your hand and the clouds use each eye's asymmetric
  projection and your IPD (eye spacing).
- Head tracking: rotation, roll and positional lean. The view recenters automatically when
  you put the headset on, and **Home** recenters by hand.
- Your head is your aim. The crosshair, block targeting, hitting and walking direction all
  follow where you look. The mouse turns your body left and right, and up and down comes
  only from your head. Servers just see normal mouse movement.
- The HUD and menus (hotbar, chat, Lunar HUD mods) float in front of you in stereo, with a
  drawn mouse cursor for menus.
- The desktop window mirrors the left eye.
- OptiFine shader packs work per eye (experimental, see [Shaders](#shaders)).
- Eye-tracked foveated rendering on NVIDIA GPUs (see [Foveated rendering](#foveated-rendering)).
- Fails safe: if anything goes wrong, VR turns off and the game keeps running.

## Compatibility

| | |
|---|---|
| Minecraft | **1.8.9 only**. Other versions load the agent but aren't changed. |
| Lunar Client | any build that remaps 1.8.9 to MCP names at runtime (Lunar 3.x does). If Lunar changes the renderer, the agent logs `failed to patch EntityRenderer` and stays inactive. |
| OS | **Linux x86_64**. The native bridge uses GLX/libGL. Windows would need a port of `native/lcvr.cpp` (wgl instead of glX, a DLL build). |
| Headset | anything SteamVR drives: Steam Frame, Index, Quest via Steam Link, and so on |
| GPU | any with OpenGL 3.0+ framebuffer support. Tested on an RTX 2070 Super. |

## Build

The agent is written in Kotlin and the headset bridge in C++. You need `kotlinc` 2.x, a
JDK 17+ (for `java` and the JNI headers), g++ and curl. `build.sh` downloads ASM and the
OpenVR SDK into `third_party/`.

```sh
./build.sh
# NixOS:
nix shell nixpkgs#kotlin nixpkgs#jdk17 nixpkgs#gcc -c ./build.sh
```

This produces `dist/lcvr.jar`, `dist/liblcvr.so` and `dist/libopenvr_api.so`, which must
stay together in one folder. The jar bundles the parts of ASM and the Kotlin standard
library it uses, relocated under `lcvr/` (see `tools/Shade.kt`), so nothing else needs to
be installed for the game.

### Nix

The repo is a flake (x86_64-linux):

```sh
nix run github:AfkaraLP/lunarclient-steamvr-patch    # build, set up the 1.8 profile, start Lunar
nix build                                             # result/share/lunar-vr/{lcvr.jar,liblcvr.so,libopenvr_api.so}
nix develop                                           # toolchain + pinned deps; ./build.sh works offline
nix profile install github:AfkaraLP/lunarclient-steamvr-patch   # puts `lunar-vr` on PATH
```

`nix run` and `lunar-vr` are the launch script below, pointed at the agent in the Nix store.
`LCVR_OPTS` and `LUNAR_CMD` work as usual, and `lunarclient` is taken from `PATH`. The
store path is written into the profile on every launch, so after an update or garbage
collection just launch through `lunar-vr` again. There's also `overlays.default`, which
provides `pkgs.lunar-vr`.

Debug dumps go to `~/.cache/lcvr/debug/` when the agent runs from the read-only store. In a
source checkout they go to `debug/`. `-Dlcvr.debugDir=...` overrides both.

## Install and run

1. Start SteamVR and connect your headset.
2. Quit Lunar completely, then start it with:
   ```sh
   ./launch-lunar-vr.sh
   ```
   This adds `-javaagent:<repo>/dist/lcvr.jar` to the **1.8 profile's own** JVM
   arguments in `~/.lunarclient/db/profiles.db`, keeping anything already there, and
   then starts Lunar. A profile's JVM arguments override Lunar's global "JVM Arguments"
   setting, and Lunar resets `JAVA_TOOL_OPTIONS` and similar environment variables, so
   those routes don't work.
   - If your Lunar command isn't `lunarclient` (an AppImage, say):
     `LUNAR_CMD=/path/to/Lunar.AppImage ./launch-lunar-vr.sh`
   - Or add the `-javaagent:...` argument by hand in the 1.8 profile's settings.
3. Launch 1.8.9, sit facing forward and press **Home** if the view is off.

You'll know it's working when `~/.lunarclient/profiles/1.8/logs/latest.log` shows
`[lcvr] patched EntityRenderer` and `[lcvr] VR active`.

### Game settings

While VR is active, the agent turns off VSync, the FPS cap, view bobbing, anaglyph, pause on
lost focus and the vignette. The vignette would otherwise turn the transparent HUD into a
black sheet, and the lenses vignette on their own. You need to turn off **OptiFine Fast
Render**, **anti-aliasing** and Lunar motion blur yourself.

Keep the Minecraft window **visible on a monitor**. Compositors throttle hidden windows
(Hyprland's `misc:render_unfocused_fps` defaults to 15), and the game can't present
frames faster than its window does. Low FPS makes close objects swim, because SteamVR can
only correct rotation between frames, not position.

### Shaders

OptiFine shader packs are supported, experimentally:

- Packs load from `<game dir>/shaderpacks/`, which is `~/.minecraft/shaderpacks/` for Lunar.
  The settings live in `~/.minecraft/optionsshaders.txt`. Use a pack that supports
  OptiFine on 1.8.9.
- In the pack's shader options, set **Render Quality** (`renderResMul`) to 1x or lower and
  **Shadow Quality** to 1x. At 2x, every eye renders at twice its already-large resolution.
- Each eye renders the full shader pipeline at eye resolution, so expect roughly half the FPS
  of the same pack on a monitor. Start with `scale=0.5` or `0.6`.
- The shadow map is rendered once per frame and shared by both eyes.
- OptiFine's once-per-frame shader setup is redone for the second eye. Without that, the
  second eye renders with no shader programs at all.
- Each eye keeps its own previous-frame camera matrices, so motion blur and reprojection
  don't mistake the distance between your eyes for movement.
- **Turn off TAA** (and other temporal effects) in the pack's settings. Their history
  buffers are shared between the eyes and ghost or smear in VR.
- Packs that do heavy screen-space effects (SSR, wide bloom) can look slightly different in
  each eye. That's inherent to screen-space techniques in stereo.

## Options

Put options in `LCVR_OPTS` for the launch script, e.g. `LCVR_OPTS=scale=0.6,hudDist=1.2`,
or append `=key=value,...` to the `-javaagent` path.

| key | default | meaning |
|---|---|---|
| `scale` | `0.75` | multiplier on SteamVR's recommended per-eye resolution; lower is faster |
| `hudDist` | `1.6` | HUD distance (m) |
| `hudWidth` | `1.5` | HUD width (m) |
| `recenterKey` | `199` | LWJGL key code for recenter / retry VR start (199 = Home) |
| `mirror` | `true` | show the left eye in the desktop window |
| `positional` | `true` | apply head translation (leaning) |
| `foveation` | `eye` | foveated rendering: `eye` (follows your gaze), `fixed` (lens center) or `off`, see [Foveated rendering](#foveated-rendering) |
| `foveaInner` | `20` | degrees around the fovea center shaded at full rate |
| `foveaOuter` | `35` | degrees out to which one shade per 2x2 pixels is used; beyond it, per 4x4 |

## Foveated rendering

On headsets with eye tracking (Steam Frame), the world is shaded at full rate only around
where you're looking. Further out, one fragment is shaded per 2x2 pixels, and beyond
`foveaOuter` per 4x4. Geometry and edges stay at full resolution; only shading gets coarser.
It uses variable rate shading (`GL_NV_shading_rate_image`), so it needs an **NVIDIA GTX 16xx /
RTX 20xx or newer**. On other GPUs the log says `foveated rendering unavailable` and
everything renders as before.

- The gaze comes from SteamVR's eye tracking input. The agent writes an action manifest that
  binds it to `/user/head/eyetracking` into `~/.cache/lcvr/input/`, and falls back to
  `IVRSystem::GetEyeTrackedFoveationCenter`. The log says `eye tracking active` once gaze
  arrives, or warns after 10 s without it. During blinks the last gaze is kept for half a
  second, then the lens center is used until tracking returns.
- Without eye tracking, `foveation=eye` does nothing. `foveation=fixed` foveates around the
  lens center on any headset.
- Only the world and hand are foveated. The HUD, menus and the OptiFine shadow map are always
  full rate.
- The gain depends on where the frame time goes. Vanilla Minecraft is mostly limited by the CPU,
  so expect little there. Shader packs are limited by fragment shading, and that's where it helps.
  Keep the shader pack's **Render Quality** at 1x, since the rate image is sized for the eye buffer.
- The desktop mirror shows the left eye with its blurry periphery.
- If the sharp area seems to sit somewhere other than where you look, drop a frame dump
  (see [Troubleshooting](#troubleshooting)): `state.txt` lists the gaze in NDC, and `eye0.png`
  shows where it's sharp. Please report it, along with your headset.

## Troubleshooting

Look for the `[lcvr]` lines in `~/.lunarclient/profiles/1.8/logs/latest.log`.

- **No `[lcvr]` lines:** the agent wasn't loaded. Check the "Custom JVM Arguments" line in
  `~/.lunarclient/logs/launcher/main.log`.
- **`OpenVR not started: …`:** SteamVR wasn't running or no headset was connected. Press Home
  to retry.
- **`framebuffers seem disabled`:** turn off Fast Render.
- **Low FPS or judder:** lower `scale`. Minecraft renders the world twice per frame.
- **Frame dump:** run `mkdir -p debug && touch debug/request` while in game (or
  `~/.cache/lcvr/debug/` when installed with Nix). Within a second the folder contains
  `eye0.png`, `eye1.png`, `hud.png`, `mirror.png` and `state.txt` (pose, projection,
  angles), showing exactly what's sent to the headset. `-Dlcvr.dump=true` also writes the
  patched class there.

## How it works

`src/lcvr/Patcher.kt` rewrites `net.minecraft.client.renderer.EntityRenderer` as Lunar
defines it. The injected code is straight-line only, so the existing stack map frames stay
valid:

- `updateCameraAndRender`: at the start, wait for the headset pose. At each return, capture
  the GUI, draw it into both eyes and submit them to SteamVR.
- `renderWorld`: with a shader pack loaded, Minecraft reports the eye size for the whole
  world render, because OptiFine's `Shaders.beginRender` sizes its buffers there.
- `renderWorldPass`: the body moves to `lcvr$renderWorldPass`, and the agent calls it once
  per eye with Minecraft's framebuffer and display size swapped for the eye buffer.
- `orientCamera`: applies head roll and head position.
- every `Project.gluPerspective` call: during eye passes it's replaced with the eye's
  projection (`GetProjectionMatrix`) with the eye offset folded in. If you build it from
  `GetProjectionRaw` instead, note that its "top" and "bottom" are named backwards (see
  OpenVR's driver docs). Getting that wrong shifts the view about 11° vertically on the
  Steam Frame.

Patched game code reaches the agent through `MethodHandle`s stored in system properties,
because Lunar runs the game in its own module and class loader. Game objects are accessed
by reflection after opening the packages with `Instrumentation.redefineModule`.
`native/lcvr.cpp` is a small JNI bridge to OpenVR plus a few GL helpers. It saves and
restores every piece of GL state it touches, so Minecraft's `GlStateManager` cache stays
correct.

## Limitations

- Seated or standing with keyboard and mouse. Motion controllers aren't used.
- The HUD is locked to your head.
- Fast Render isn't supported. Shader TAA (temporal anti-aliasing) isn't supported, see [Shaders](#shaders).

## License

MIT, see [LICENSE](LICENSE). Third-party code: [ASM](https://asm.ow2.io/) (BSD-3-Clause,
shaded into `lcvr.jar`) and the [OpenVR SDK](https://github.com/ValveSoftware/openvr)
(BSD-3-Clause, `libopenvr_api.so`).
