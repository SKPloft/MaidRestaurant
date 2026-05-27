# AGENTS.md - Maid Restaurant

This repository is a Minecraft Java Edition mod. Work on it with the judgment of experienced Java engineers and Minecraft mod programmers: respect the game lifecycle, keep server and client behavior separated, preserve compatibility with integrated mods, and verify changes through Gradle or an in-game run when behavior depends on Minecraft runtime state.

## Project Context

- Mod name: Maid Restaurant
- Mod id: `maid_restaurant`
- Loader/platform: NeoForge
- Minecraft version: `1.21.1`
- Java version: `21`
- Main package: `com.mastermarisa.maid_restaurant`
- Core integrations: Touhou Little Maid, Kaleidoscope Cookery, Farmer's Delight, Bakeries, Create, Patchouli, Ponder, Flywheel, Iris compile-only

## Engineering Posture

- Prefer small, reversible changes that match existing project structure.
- Read nearby code before editing; follow existing naming, package layout, registration patterns, and task-state style.
- Preserve gameplay behavior unless the task explicitly asks to change it.
- Avoid adding dependencies unless the user explicitly asks for them.
- Treat warnings from Minecraft, NeoForge, mixins, registries, networking, and data generation as meaningful until proven otherwise.
- Keep code readable for mod maintainers: simple control flow, precise names, and no abstraction unless it removes real duplication or guards an API boundary.

## Minecraft Modding Rules

- Keep logical server and physical client code separated. Do not reference client-only classes from common/server paths unless guarded by NeoForge dist boundaries.
- Register content through the existing `init` and registry classes; avoid ad hoc static initialization that can run before registries are ready.
- Use stable resource locations based on `MaidRestaurant.MOD_ID`.
- Be careful with world access:
  - Mutate game state on the server side.
  - Use `ServerLevel` where server-only behavior is required.
  - Do not assume block entities, entities, players, menus, or capabilities still exist after a tick boundary.
- For maid AI and behavior tasks, preserve tick-rate checks, memory/state transitions, and failure paths. A stuck task is a gameplay bug.
- For inventory and item handlers, simulate before extracting/inserting when correctness depends on capacity.
- For requests and storage, maintain NBT serialization compatibility unless a migration is intentionally added.
- For networking payloads, validate side, player, entity id/UUID, indices, and action codes before mutating state.
- For mixins and access transformers, keep changes narrow and document why direct API use is not sufficient.
- For compat code, isolate integration assumptions under the relevant `compat` package and fail gracefully when external state is missing.

## Java Style

- Target Java 21, but do not use clever language features where plain Java is clearer.
- Prefer early returns for invalid Minecraft state.
- Use `@Nullable` consistently when values can be absent.
- Avoid broad `Objects.requireNonNull` in tick logic unless the surrounding checks prove the object cannot disappear.
- Keep public APIs stable under `api` unless a breaking change is intentional.
- Keep client UI code deterministic and avoid leaking client rendering objects into common logic.
- Do not swallow exceptions silently; use project logging/debug utilities or a narrow fallback.

## Assets, Data, And Resources

- Keep generated resources under `src/generated/resources` and source resources under `src/main/resources`.
- Run data generation when changing tags, recipes, advancements, language files, models, or generated metadata.
- Keep `src/main/templates` and generated metadata consistent with `gradle.properties`.
- Verify mixin config entries when adding, renaming, or deleting mixins.
- Avoid changing binary assets unless the task requires it.

## Build And Verification

Use the Gradle wrapper from the repository root.

- Compile/check: `./gradlew build`
- Data generation: `./gradlew runData`
- Client runtime check: `./gradlew runClient`
- Server runtime check: `./gradlew runServer`
- Game test server: `./gradlew runGameTestServer`

Before claiming completion:

- Run the smallest Gradle task that proves the change.
- Run `./gradlew build` for non-trivial Java, registry, networking, mixin, or resource changes.
- Run the relevant Minecraft runtime task when behavior depends on actual game state, rendering, AI ticks, or integration mods.
- If verification cannot run because dependencies or network access are unavailable, report that clearly with the command attempted and the observed blocker.

## Change Discipline

- Do not rewrite broad systems unless the user asked for a refactor.
- Preserve existing save data, request data, and network compatibility by default.
- Keep generated output out of commits unless the source change requires generated resources.
- Do not edit Gradle repositories, dependency versions, mappings, or mod metadata casually.
- If a change affects gameplay balance, AI behavior, or player data, call that out in the final report.

