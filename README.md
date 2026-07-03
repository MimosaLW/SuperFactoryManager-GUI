# Super Factory Manager — Visual GUI (SFM-GUI)

A node / wire based **visual program editor** for the Minecraft mod
[Super Factory Manager (SFM)](https://github.com/TeamDman/SuperFactoryManager).
Build factory programs by dragging nodes and wiring them together instead of
writing SFML (Super Factory Manager Language) by hand.

> 一个为 [Super Factory Manager](https://github.com/TeamDman/SuperFactoryManager)
> 打造的**节点/连线式可视化编程界面**。通过拖拽节点、连线来搭建工厂程序，
> 而不必手写 SFML 代码。

- **Minecraft**: 1.21.1
- **Mod loader**: NeoForge 21.1.206
- **Requires**: Super Factory Manager (installed alongside this addon)

---

## Features / 功能

- Classic *Steve's Factory Manager* styled node canvas (pan / zoom).
- Trigger, Input, Output, If, Forget and Raw statement nodes.
- Series (chain) wiring between statements.
- Searchable resource picker with item icons, fluids and Mekanism chemicals.
- Pinyin-aware search (中文拼音搜索) via the bundled
  [PinIn](https://github.com/Towdium/PinIn) library.
- Live SFML preview with syntax highlighting (reuses SFM's own highlighter).
- Round-trips to plain SFML, so programs stay compatible with SFM.

---

## Attribution & Sources / 参考来源

This project stands on the shoulders of two prior works and is **not** an
original factory-automation design:

### 1. Super Factory Manager (SFM) — TeamDman
- Repository: <https://github.com/TeamDman/SuperFactoryManager>
- Author: **TeamDman**
- License: **Mozilla Public License 2.0**

This addon is built **on top of** SFM. It compiles and runs against SFM's public
API, reuses SFM's SFML parser / AST, program packet, label handling and syntax
highlighter, and produces standard SFML programs that SFM executes. All of the
factory-automation logic, the SFML language, and the underlying manager belong to
SFM. This project only adds a visual editing front-end.

### 2. Steve's Factory Manager (the node-editor UI inspiration)

The visual node-editor design — the node canvas, menu strips, connection nubs and
the `flow_components` sprite layout — is inspired by and ported from the classic
*Steve's Factory Manager* and its community reimplementations:

- **ModJam3 / Steve's Factory Manager** — Vswe (original author):
  <https://github.com/Vswe/ModJam3>
- **Steve's Factory Manager (Reborn / continuation)** — gigabit101:
  <https://github.com/gigabit101/StevesFactoryManager>

The classic UI look and feel (frames, menu strips, connection nubs, the flow
component sprite sheet) originate from these projects.

If you enjoy this addon, please support the original **Super Factory Manager** and
the authors of **Steve's Factory Manager** (Vswe and gigabit101).

> 本项目的节点编辑器 UI 参考并移植自经典的 *Steve's Factory Manager* 及其社区续作：
> Vswe 的 [ModJam3](https://github.com/Vswe/ModJam3) 与
> gigabit101 的 [StevesFactoryManager](https://github.com/gigabit101/StevesFactoryManager)。

---

## License / 开源协议

This project is licensed under the **Mozilla Public License 2.0 (MPL-2.0)**, the
same license as Super Factory Manager, to remain compatible with the upstream code
it builds upon.

- Full text: see [`LICENSE`](./LICENSE) or <https://www.mozilla.org/en-US/MPL/2.0/>.
- Each file governed by the MPL is subject to the terms of the MPL. If a copy of
  the MPL was not distributed with a file, you can obtain one at the URL above.

> 本项目采用 **Mozilla Public License 2.0 (MPL-2.0)** 协议开源，与其所依赖的
> Super Factory Manager 保持一致，以兼容上游代码。协议全文见 [`LICENSE`](./LICENSE)。

### Bundled third-party code
- **PinIn** (Towdium) — bundled for Chinese pinyin search, MIT License:
  <https://github.com/Towdium/PinIn>

---

## Building / 构建

Requires JDK 21.

```bash
./gradlew build        # compile + run tests, produces build/libs/*.jar
./gradlew jarJar       # produces the distributable jar with PinIn bundled (-all.jar)
```

The build depends on an SFM jar placed at `libs/sfm-4.34.0.jar` (see
`gradle.properties` → `sfm_jar`), and on the Mekanism API (compile-only, for
chemical resource icons).

## Usage / 使用

1. Install NeoForge 1.21.1, Super Factory Manager, and this addon.
2. Open an SFM Manager, then open the visual editor from the manager screen.
3. Drag nodes from the left toolbar, wire them, and save — the program is written
   back as SFML that SFM runs.
