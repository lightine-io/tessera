package io.lightine.tessera.mrz.camera.ui

import io.lightine.tessera.mrz.camera.CameraError

/**
 * Consumer configuration for [MrzScannerScreen] — the one place a host app tunes the default UI without
 * forking it. Deliberately small: the default UI is opinionated, and every option here is a promise the
 * SDK keeps under [ADR-007](https://lightine.youtrack.cloud/articles/TES-A-37) once 0.5.0 tags.
 *
 * **Reader, not oracle (Principle 1).** Nothing here lets the UI make a trust decision about a document;
 * options govern appearance and the permission hand-off only.
 *
 * @property useDynamicColor opt into Material You dynamic color (sourced from the device wallpaper on
 *   Android 12+). **Off by default** — the module ships a fixed, self-contained Material 3 scheme so its
 *   look does not depend on the host app's theme or the user's wallpaper. On devices below Android 12 the
 *   flag has no effect (dynamic color is unavailable) and the fixed scheme is used.
 * @property onRequestPermission invoked when the UI needs the camera permission it does not hold — the
 *   host app requests it (the SDK never requests a permission on the consumer's behalf; `scope.md`
 *   "permission boundary"). `null` (the default) means the host handles the permission entirely outside
 *   this screen; the UI still reacts to a
 *   [`CameraError.PermissionDenied`][CameraError.PermissionDenied] it observes, it just does not prompt.
 */
public class MrzScannerConfig(
    public val useDynamicColor: Boolean = false,
    public val onRequestPermission: (() -> Unit)? = null,
)
// NOTE (pre-0.5.0-tag): the config-evolution strategy — how new options are added after the tag without
// breaking the constructor's binary signature under ADR-007 (defaulted params vs a builder) — is settled
// before the freeze, not in this scaffold. Kept a plain class with defaulted params for now.
