package com.rdsinteractive.divan.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.rdsinteractive.divan.MainActivity
import com.rdsinteractive.divan.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AppInfo(
    val packageName: String,
    val component: ComponentName,
    val label: String,
    /** 16:9 TV banner when the app ships one, otherwise its square icon. */
    val image: ImageBitmap?,
    val isBanner: Boolean,
)

/** Reads launchable apps the way a TV home screen should: leanback first, phone launchers as fallback. */
class AppRepository(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    suspend fun loadApps(iconOverrides: Map<String, String>): List<AppInfo> = withContext(Dispatchers.IO) {
        val leanback = query(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val phone = query(Intent.CATEGORY_LAUNCHER)
        val byPackage = LinkedHashMap<String, ResolveInfo>()
        (leanback + phone).forEach { info ->
            val name = info.activityInfo.packageName
            if (name != context.packageName) byPackage.putIfAbsent(name, info)
        }
        (byPackage.values.map { toAppInfo(it, iconOverrides[it.activityInfo.packageName]) } + settingsCard(iconOverrides))
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Divan's own settings as a card in the row, where the system Settings app used to sit;
     * the gear at the top opens the system ones. Opening it shows the settings screen.
     */
    private fun settingsCard(iconOverrides: Map<String, String>): AppInfo = AppInfo(
        packageName = context.packageName,
        component = ComponentName(context, MainActivity::class.java),
        label = AppLocale.text(context).getString(R.string.divan_settings),
        // The card keeps the look the system Settings card had (the user preferred it).
        image = customIcon(context.packageName, iconOverrides[context.packageName])
            ?: systemSettingsBanner()
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.divan_settings_banner)?.asImageBitmap(),
        isBanner = true,
    )

    fun launchIntent(packageName: String): Intent? =
        (packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun detailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun query(category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        // ResolveInfoFlags is API 33; the Chromecast HD runs Android 12.
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
    }

    private fun systemSettingsBanner(): ImageBitmap? = runCatching {
        packageManager.getApplicationBanner(SYSTEM_SETTINGS)?.toBitmap(320, 180)?.asImageBitmap()
    }.getOrNull()

        private fun toAppInfo(info: ResolveInfo, iconOverride: String?): AppInfo {
        val activity = info.activityInfo
        val label = info.loadLabel(packageManager).toString()
        customIcon(activity.packageName, iconOverride)?.let { custom ->
            return AppInfo(
                packageName = activity.packageName,
                component = ComponentName(activity.packageName, activity.name),
                label = label,
                image = custom,
                isBanner = true,
            )
        }
        val banner = activity.loadBanner(packageManager) ?: activity.applicationInfo.loadBanner(packageManager)
        val drawable = banner ?: activity.loadIcon(packageManager)
        val image = runCatching {
            if (banner != null) drawable.toBitmap(320, 180) else drawable.toBitmap(192, 192)
        }.getOrNull()?.asImageBitmap()
        return AppInfo(
            packageName = activity.packageName,
            component = ComponentName(activity.packageName, activity.name),
            label = label,
            image = image,
            isBanner = banner != null,
        )
    }

    /**
     * Our own 16:9 picture for an app with an ugly banner: the file picked in the launcher,
     * else `<package>.png|jpg|webp` in [ICONS_DIR]. An empty [override] keeps the app's banner.
     */
    private fun customIcon(packageName: String, override: String?): ImageBitmap? {
        if (override == "") return null
        // A banner with words comes per language: `<package>.<lang>.png` first (en, ru, zh), then
        // `<package>.png`. The copy is per language too, so a language change never shows the old one.
        val lang = AppLocale.locale().language
        val copy = File(context.filesDir, "icons/$packageName.$lang")
        // Right after boot the shared storage is mounted after Divan starts: the copy inside
        // Divan stands in, so the app's own banner does not flash for the first seconds.
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            return copy.takeIf { it.canRead() }?.let { decodeSampled(it.path, targetWidth = 480) }?.asImageBitmap()
        }
        // a picked file has its own language variants beside it (`x.png` → `x.en.png`)
        val picked = override?.let(::File)?.takeIf { it.canRead() }?.let { f ->
            IMAGE_EXTENSIONS.map { File(f.parentFile, "${f.nameWithoutExtension}.$lang.$it") }.firstOrNull { it.canRead() } ?: f
        }
        val file = picked
            ?: listOf("$packageName.$lang", packageName)
                .flatMap { name -> IMAGE_EXTENSIONS.map { File(ICONS_DIR, "$name.$it") } }
                .firstOrNull { it.canRead() }
        if (file == null) {
            copy.delete()
            return null
        }
        if (copy.length() != file.length() || copy.lastModified() < file.lastModified()) {
            runCatching { copy.parentFile?.mkdirs(); file.copyTo(copy, overwrite = true) }
        }
        return decodeSampled(file.path, targetWidth = 480)?.asImageBitmap()
    }

    companion object {
        const val ICONS_DIR = "/sdcard/Pictures/Divan/icons"
        private const val SYSTEM_SETTINGS = "com.android.tv.settings"
    }
}
