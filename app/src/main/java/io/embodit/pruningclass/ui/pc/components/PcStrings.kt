package io.embodit.pruningclass.ui.pc.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.pruningclass.domain.model.LanguagePref
import java.util.Locale

/**
 * Localised resources for the language chosen in the app ("System default / 中文 / English").
 *
 * The language switches at run time without restarting the Stage: the screen provides a Resources
 * object for the chosen locale through this local (provided outside the SpatialView; the feasibility
 * spike verified that such a local reaches attachment panels) and every panel reads its copy from it.
 * `LocalContext` is deliberately not overridden, so SpatialUI popups keep the real window context.
 */
val LocalPcResources = staticCompositionLocalOf<Resources> { error("LocalPcResources not provided") }

fun localizedResources(context: Context, language: LanguagePref): Resources {
    val tag =
        when (language) {
            LanguagePref.SYSTEM -> return context.resources
            LanguagePref.ZH -> "zh-CN"
            LanguagePref.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

/** String for the language chosen in the app. */
@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalPcResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalPcResources.current.getString(id, *args)

@Composable
@ReadOnlyComposable
fun rq(@PluralsRes id: Int, count: Int): String = LocalPcResources.current.getQuantityString(id, count, count)

/** String by resource name (lesson / rule / defect keys built from model names). Falls back to the name itself. */
@Composable
@ReadOnlyComposable
fun rsn(name: String, vararg args: Any): String = stringByName(LocalPcResources.current, name, *args)

fun stringByName(res: Resources, name: String, vararg args: Any): String {
    @Suppress("DiscouragedApi")
    val id = res.getIdentifier(name, "string", "io.embodit.pruningclass")
    return if (id == 0) name else if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
}
