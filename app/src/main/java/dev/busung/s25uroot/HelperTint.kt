package dev.busung.s25uroot

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The colours this app's window is drawn with, in the plain values the helper can be handed.
 *
 * The helper is a second APK. It cannot see this app's resources or its scheme, and the theme it is
 * drawn in is the platform's `DeviceDefault` - the OEM's own palette, which is not the Material palette
 * this app draws with. In particular `colorAccent` is not this app's `primary`: the app's accent is
 * either Material You's tonal palette or a scheme generated here from a chosen seed, and neither of
 * those is what the platform puts in that attribute. So a helper that read its own theme resolved
 * against a different palette and looked like a different app, which is exactly what it did.
 *
 * The values are therefore handed over rather than looked up, and they are the ones the screen is
 * *drawing with* - [update] is called from the theme itself - so the two cannot drift: there is no
 * second palette here to keep in step, only the one the app already computed.
 *
 * ## Why this is null before a screen has drawn
 *
 * Nothing here is known until the theme has composed, and a launch that never saw a screen - the boot
 * that starts the helper with nobody watching - has nothing to hand over. [value] is null there and the
 * launch carries no extra, which leaves the helper on its own theme and its own palette: the behaviour it
 * had before any of this existed, rather than a guess at colours nobody has computed.
 *
 * The same is true of the helper opened from the launcher, where there is no app on the other end of the
 * launch at all. Both are the states this can honestly say nothing about, so both fall back rather than
 * inventing an accent.
 */
internal object HelperTint {

    /**
     * The extra the helper reads this from.
     *
     * Held to the helper's own constant by a test that reads both sources: the two modules share no code,
     * and a rename on one side with no rename on the other is an extra nobody reads.
     */
    const val EXTRA = "rmg.tint"

    @Volatile
    private var encoded: String? = null

    /** What to send, or null when no screen has drawn yet. */
    val value: String? get() = encoded

    /**
     * Takes the scheme the app is drawing with.
     *
     * [dark] rather than a luminance test on one of the colours: the app's light/dark choice is its own
     * setting, not the phone's, and the helper's few decisions that depend on it - which shade reads as a
     * well, how strong a pill tint may be - have to answer for the window the app chose.
     */
    fun update(scheme: ColorScheme, dark: Boolean) {
        // Named rather than positional: the helper reads these by name, and a value that arrived in the
        // wrong slot would be a screen wearing the other one's colours.
        encoded = buildString {
            append("surface=").append(hex(scheme.surface))
            append(",card=").append(hex(scheme.surfaceContainerHighest))
            append(",panel=").append(hex(scheme.surfaceContainer))
            append(",onSurface=").append(hex(scheme.onSurface))
            append(",onSurfaceVariant=").append(hex(scheme.onSurfaceVariant))
            append(",accent=").append(hex(scheme.primary))
            append(",dark=").append(if (dark) "1" else "0")
        }
    }

    private fun hex(color: Color): String = "%08x".format(color.toArgb())
}
