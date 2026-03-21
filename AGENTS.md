# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Project Overview
Roo Code for JetBrains (CE) is a "Simulation & Adaptation" bridge that runs VSCode extensions (Node.js) inside JetBrains IDEs (JVM).

## Critical Non-Obvious Rules

### 1. RPC & IPC (The "Translation" Layer)
- **Naming Convention**: Kotlin RPC interface methods (in `jetbrains_plugin/src/main/kotlin/com/roocode/jetbrains/actors/`) MUST use standard `camelCase`. **DO NOT** add `$` prefix; the `RPCProtocol` adds it automatically.
- **Data Transfer (DTO)**: Avoid language-specific types like Kotlin's `Pair` or `Triple`. Use `List<Any>` (JS Array) or `Map<String, Any?>` (JS Object).
- **Serialization**: Kotlin `List<Pair<A, B>>` serializes to `[{ "first": ..., "second": ... }]`, which breaks JS `for...of` destructuring. Use `List<List<Any>>` instead.

### 2. Build & Environment
- **Initialization**: Always run `./scripts/setup.sh` first. It handles git submodules and patches.
- **Build Modes**: Controlled by `-PdebugMode` in Gradle:
  - `idea`: Local dev, hot-reloads resources from `debug-resources/`.
  - `release`: Production, requires `platform.zip` (Node.js runtime).
  - `none`: Lightweight CI/Test mode.
- **Node.js Runtime**: The `extension_host` is a separate process. Logs are in the IDE's "Extension Host" output channel or `idea.log`.

### 3. Architecture Constraints
- **Master-Slave**: IntelliJ Plugin is the Master (Server), `extension_host` is the Slave (Client).
- **UI**: Hybrid mode. Tool windows are Swing; content is JCEF (Chromium).
- **Webview Debugging**: Use Command Palette > `Developer: Open Webview Developer Tools`.

## Essential Commands
- **Setup**: `./scripts/setup.sh`
- **Build All**: `./scripts/build.sh`
- **Run IDE (Dev)**: `cd jetbrains_plugin && ./gradlew runIde -PdebugMode=idea`
- **Build Extension Host**: `cd extension_host && npm run build`
