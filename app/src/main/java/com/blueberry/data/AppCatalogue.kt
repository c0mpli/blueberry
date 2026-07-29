package com.blueberry.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.blueberry.router.AppEntry
import com.blueberry.router.Catalogue

/**
 * The installed-app catalogue, read through [LauncherApps].
 *
 * `LauncherApps.getActivityList` rather than `PackageManager.queryIntentActivities` because it
 * enumerates work and private profiles properly. It does *not*, contrary to a common belief,
 * exempt the caller from Android 11+ package visibility — LauncherAppsService passes the calling
 * uid straight through to the package manager — which is why the manifest carries a `<queries>`
 * entry for MAIN/LAUNCHER. Without that this returns almost nothing and throws nothing.
 *
 * No role is required to read this. The drawer works before Blueberry is ever set as the default
 * launcher; only the shortcut APIs need ROLE_HOME.
 */
class AppCatalogue(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    /** package -> where to launch it. Kept beside the pure [Catalogue], which holds no Android types. */
    private val launchTargets = HashMap<String, Target>()

    private data class Target(val component: ComponentName, val user: UserHandle)

    @Volatile
    var current: Catalogue = Catalogue.EMPTY
        private set

    /**
     * Rebuild from scratch. Cheap enough to run on a package change, too slow for the critical
     * path — callers hold the result and rebuild on the [LauncherApps.Callback].
     */
    fun refresh(): Catalogue {
        val entries = LinkedHashMap<String, AppEntry>()
        val targets = HashMap<String, Target>()

        for (user in userManager.userProfiles) {
            val activities: List<LauncherActivityInfo> = try {
                launcherApps.getActivityList(null, user)
            } catch (e: SecurityException) {
                // Cross-profile access can be refused; the rest of the profiles are still usable.
                Log.w(TAG, "cannot enumerate activities for $user", e)
                continue
            }

            for (info in activities) {
                val pkg = info.applicationInfo.packageName
                if (pkg == context.packageName) continue // don't list ourselves in our own drawer
                // One entry per package. Apps that publish several launcher activities are rare and
                // showing the first is the right default for a voice surface.
                if (pkg in entries) continue
                entries[pkg] = AppEntry(pkg, info.label.toString())
                targets[pkg] = Target(info.componentName, info.user)
            }
        }

        synchronized(launchTargets) {
            launchTargets.clear()
            launchTargets.putAll(targets)
        }
        return Catalogue(entries.values.sortedBy { it.label.lowercase() }).also { current = it }
    }

    fun icon(packageName: String, density: Int = 0): Drawable? {
        val target = synchronized(launchTargets) { launchTargets[packageName] } ?: return null
        return try {
            launcherApps.getActivityList(target.component.packageName, target.user)
                .firstOrNull { it.componentName == target.component }
                ?.getIcon(density)
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot load icon for $packageName", e)
            null
        }
    }

    /**
     * Launch through [LauncherApps] rather than a bare intent so work-profile apps start in the
     * right user.
     */
    fun launch(packageName: String): Boolean {
        val target = synchronized(launchTargets) { launchTargets[packageName] } ?: return false
        return try {
            launcherApps.startMainActivity(target.component, target.user, null, null)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot launch $packageName", e)
            false
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no launcher activity for $packageName", e)
            false
        }
    }

    /** Package install, removal or replacement. The catalogue hash changes; caches invalidate. */
    fun observeChanges(onChanged: () -> Unit): LauncherApps.Callback {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = onChanged()
            override fun onPackageAdded(packageName: String, user: UserHandle) = onChanged()
            override fun onPackageChanged(packageName: String, user: UserHandle) = onChanged()
            override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = onChanged()
            override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = onChanged()
        }
        launcherApps.registerCallback(callback)
        return callback
    }

    fun stopObserving(callback: LauncherApps.Callback) {
        launcherApps.unregisterCallback(callback)
    }

    private companion object {
        const val TAG = "AppCatalogue"
    }
}
