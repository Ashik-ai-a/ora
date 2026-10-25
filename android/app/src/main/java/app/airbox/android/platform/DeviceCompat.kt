package app.airbox.android.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Making a background receiver survive on every brand of Android phone.
 *
 * Stock Android respects a foreground service. Many manufacturers add their own battery
 * managers that still kill it (see dontkillmyapp.com). We can't switch those off, but we
 * can detect the brand and open the exact settings screen, with one plain instruction.
 * Component names change between OS versions, so every intent is resolved first and we
 * fall back to the app's details page, which every phone has.
 */
object DeviceCompat {
    data class Fix(
        val title: String,
        val steps: String,
        val intents: List<Intent>,
    )

    val brand: String get() = Build.MANUFACTURER.lowercase()

    /** Ignoring battery optimisation is the one fix that works the same on all brands (Android 6+). */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Opens the list where the user switches Airbox to "Don't optimise" (no special permission needed). */
    fun batteryOptimizationIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** Brand-specific extra step, or null on phones that behave like stock Android (Pixel, Motorola, Nokia...). */
    fun brandFix(): Fix? = when {
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> Fix(
            "Allow Autostart (Xiaomi / Redmi / POCO)",
            "Turn on Autostart for Airbox, then in Battery saver choose “No restrictions”.",
            listOf(component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        )
        brand.contains("samsung") -> Fix(
            "Keep Airbox out of Sleeping apps (Samsung)",
            "Battery → Background usage limits: remove Airbox from Sleeping and Deep sleeping apps, or add it to Never sleeping apps.",
            listOf(
                component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                component("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ),
        )
        brand.contains("huawei") || brand.contains("honor") -> Fix(
            "Manage app launch manually (Huawei / Honor)",
            "App launch: switch Airbox to Manage manually and keep Auto-launch, Secondary launch and Run in background on.",
            listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ),
        )
        brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> Fix(
            "Allow background activity (OPPO / realme / OnePlus)",
            "Battery → App battery management: set Airbox to Allow background activity, and turn on Auto launch.",
            listOf(
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
                component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            ),
        )
        brand.contains("vivo") || brand.contains("iqoo") -> Fix(
            "Allow background power use (vivo / iQOO)",
            "Battery → Background power consumption: allow Airbox, and turn on Autostart.",
            listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ),
        )
        brand.contains("asus") -> Fix(
            "Allow auto-start (ASUS)",
            "Mobile Manager → Auto-start manager: allow Airbox.",
            listOf(component("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity")),
        )
        brand.contains("tecno") || brand.contains("infinix") || brand.contains("itel") -> Fix(
            "Allow auto-start (Tecno / Infinix / itel)",
            "Phone Master → Auto-start: allow Airbox, and lock it in Recents.",
            listOf(component("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")),
        )
        brand.contains("lenovo") || brand.contains("zte") || brand.contains("nubia") || brand.contains("meizu") -> Fix(
            "Allow running in background",
            "In the app's battery settings, allow Airbox to run in the background.",
            emptyList(),
        )
        else -> null
    }

    /** First intent of [fix] this phone can actually open, else the app details page. */
    fun resolve(context: Context, fix: Fix?): Intent {
        val pm = context.packageManager
        fix?.intents?.forEach { intent ->
            val resolved = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            if (resolved != null) return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    /** What this phone supports, shown in Settings → This phone. */
    data class Capabilities(
        val sdk: Int,
        val liveUpdates: Boolean,
        val notificationPermissionNeeded: Boolean,
        val localOnlyHotspot: Boolean,
        val wifiAware: Boolean,
    )

    fun capabilities(context: Context) = Capabilities(
        sdk = Build.VERSION.SDK_INT,
        liveUpdates = Build.VERSION.SDK_INT >= 36,
        notificationPermissionNeeded = Build.VERSION.SDK_INT >= 33,
        localOnlyHotspot = Build.VERSION.SDK_INT >= 26,
        wifiAware = Build.VERSION.SDK_INT >= 26 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE),
    )
}
