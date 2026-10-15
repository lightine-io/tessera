---
paths:
  - "types/**"
---

# `types` module discipline

The `types` module holds types only: the vocabulary the rest of the SDK speaks. The
name is the contract.

- Allowed at top level: classes (data, value, sealed, abstract, regular), interfaces
  (regular, sealed, fun), enums, type aliases, objects that are a case of a sealed type,
  and `const val` intrinsic to a type (prefer the type's companion).
- Not allowed: top-level functions (extensions included), top-level non-const
  properties, and `object` declarations used as a namespace for functions.
- A type that needs a factory gets it on its companion. Shared non-type code gets its
  own module, never an exception here.

CI (`repo · rules`) fails on a top-level `fun`, `val`, or `var` under `types/src/`.
The namespace-object case has no check; it is on you.
