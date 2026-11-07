package app.eyen.child

import android.app.Activity
import android.content.Context
import android.content.Intent

/** The selected role belongs to this installation, independently of pairing tokens. */
object DeviceRole {
    const val PARENT = "parent"
    const val CHILD = "child"
    private const val STORE = "eyen_device"
    private const val KEY = "role"

    fun current(context: Context): String? {
        val saved = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY, null)
        if (saved == PARENT || saved == CHILD) return saved
        // Preserve existing child installations when upgrading to the unified APK.
        if (context.getSharedPreferences("eyen_child", Context.MODE_PRIVATE).contains("child_token")) {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(KEY, CHILD).apply()
            return CHILD
        }
        return null
    }

    fun select(context: Context, role: String) {
        require(role == PARENT || role == CHILD)
        if (role == PARENT) {
            context.getSharedPreferences("eyen_child", Context.MODE_PRIVATE).edit()
                .putBoolean("continuous_location_enabled", false)
                .putBoolean("remote_media_preapproved", false)
                .putBoolean("awaiting_background_location", false).apply()
            context.stopService(Intent(context, LocationSharingService::class.java))
        }
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(KEY, role).apply()
    }

    fun showPicker(activity: Activity) {
        activity.startActivity(Intent(activity, RoleSelectionActivity::class.java)
            .putExtra(RoleSelectionActivity.EXTRA_CHOOSE_ROLE, true))
    }

    fun ensure(activity: Activity, expected: String): Boolean {
        if (current(activity) == expected) return true
        activity.startActivity(Intent(activity, RoleSelectionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        activity.finish()
        return false
    }
}
