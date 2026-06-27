---
paths:
  - "types/**"
---

# `types` module discipline

**The `types` module is types-only.** It exists to hold the vocabulary the rest of the SDK speaks — classes (data / value / sealed / abstract), interfaces (regular, sealed, fun), enums, type aliases, and the companion objects that belong to those types. Nothing else. The module's name *is* the contract.

## What is allowed at top level

- Classes — `data`, `value` (`@JvmInline value class …`), `sealed`, `abstract`, regular `class`.
- Interfaces — regular, `sealed`, `fun` (SAM).
- Enums — `enum class`.
- Type aliases — `typealias …`.
- Companion objects within any of the above — they are part of that type's API surface.
- Object declarations that represent a singleton instance of a type — e.g., a case of a `sealed interface` (`object Loading : LoadState`). These ARE types, not function namespaces.
- `const` top-level vals that are intrinsic to a type's definition — rare; prefer placing them inside the relevant type's companion.

## What is NOT allowed at top level

- **Top-level functions.** Factory helpers, parsers, formatters, utility functions — these belong with the code that owns the operation, not with the type. If a type needs a factory, put it on the type's companion.
- **Top-level non-const properties.** No `val somethingShared = …` at file scope. State and computed values belong with the type that owns them.
- **Extension functions on third-party types.** A type's companion may define operations on instances of *that* type — those are part of the type's API. Extensions on `String`, `Instant`, third-party collections, etc., are operations on someone else's type and belong in the module that uses them.
- **Top-level `object` declarations used as function namespaces.** `object Foo { fun bar() … fun baz() … }` is a namespace for functions wearing a singleton's clothing. Not allowed.

## Why this matters

Broadly-named modules — `common`, `core`, `utils` — accumulate unrelated helpers over years until no one remembers the original boundary. The result is a junk drawer that every module depends on and no module should. Once that has happened, splitting the module back apart is a substantial migration; preventing it costs one Gradle file.

The name `types` is unambiguous in a way `core` or `common` is not. A contributor seeing a top-level utility function in `types/` should immediately recognize that something is misplaced. The published artifactId at first Maven Central release is the deliberately-chosen `tessera-types`, locked under [ADR-007](https://lightine.youtrack.cloud/articles/TES-A-37) once 0.1.1 ships per [ADR-016](https://lightine.youtrack.cloud/articles/TES-A-53); the discipline rule preserves the meaning the name promises.

## When you need shared non-type code

Create a separate module. The cost is one new directory, one `build.gradle.kts`, and one `include(":tessera-utils")` (or whatever the purpose names it) in `settings.gradle.kts`. The name describes what the module is for; future contributors know where to look and where not to look.

Do not relax this rule. The first exception is the first crack in the boundary; the tenth exception is `tessera-types-but-also-some-functions`. If the temptation to add a helper to `types/` is real, the right response is a new module, not an exception.

## Scope

Applies to source under `types/src/` — both `commonMain` and platform-specific source sets if/when added. Test code (`types/src/commonTest/`) follows the same shape: test classes are types; test helpers belong with the type they exercise, on the type's companion or as nested test fixtures.

## Cross-references

- Human-facing summary lives in [`../../docs/conventions.md`](https://lightine.youtrack.cloud/articles/TES-A-20) under "Module Boundaries."
- Module structure described in [`../../docs/architecture.md`](https://lightine.youtrack.cloud/articles/TES-A-9).
- Naming and `tessera-<module>` artifact pattern in [`../../docs/decisions/0016-maven-coordinates-and-first-publish.md`](https://lightine.youtrack.cloud/articles/TES-A-53).
- The original "name your modules deliberately" reasoning sits in the conversation that produced ADR-016 and the `domain` → `types` rename (PRs [#76](https://github.com/lightine-io/tessera/pull/76) and [#78](https://github.com/lightine-io/tessera/pull/78)).
