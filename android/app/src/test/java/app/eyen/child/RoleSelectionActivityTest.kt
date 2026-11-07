package app.eyen.child

import android.content.Context
import android.content.Intent
import android.widget.Button
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoleSelectionActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun resetPreferences() {
        listOf("eyen_device", "eyen_child", "eyen_parent").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun firstLaunchOffersBothRoles() {
        val activity = Robolectric.buildActivity(RoleSelectionActivity::class.java).setup().get()
        assertNotNull(activity.findViewById<Button>(R.id.choose_parent))
        assertNotNull(activity.findViewById<Button>(R.id.choose_child))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(DeviceRole.current(context))
    }

    @Test fun unifiedManifestHasOneLauncherAndPrivateDashboards() {
        val launchers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
        assertEquals(1, launchers.size)
        assertEquals(RoleSelectionActivity::class.java.name, launchers.single().activityInfo.name)
        listOf(MainActivity::class.java, app.eyen.parent.MainActivity::class.java).forEach {
            val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, it), 0)
            assertFalse(info.exported)
        }
    }

    @Test fun parentChoiceSavesRoleAndOpensParentDashboardAsTaskRoot() {
        val activity = Robolectric.buildActivity(RoleSelectionActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.choose_parent).performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(DeviceRole.PARENT, DeviceRole.current(context))
        assertEquals(app.eyen.parent.MainActivity::class.java.name, intent.component!!.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(activity.isFinishing)
    }

    @Test fun childChoiceSavesRoleAndOpensCodeEntry() {
        val activity = Robolectric.buildActivity(RoleSelectionActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.choose_child).performClick()
        assertEquals(DeviceRole.CHILD, DeviceRole.current(context))
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun reopeningRemembersParentChoice() {
        DeviceRole.select(context, DeviceRole.PARENT)
        val activity = Robolectric.buildActivity(RoleSelectionActivity::class.java).setup().get()
        assertEquals(app.eyen.parent.MainActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun staleChildNotificationCannotOpenChildDashboardInParentMode() {
        DeviceRole.select(context, DeviceRole.PARENT)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        assertTrue(activity.isFinishing)
        assertEquals(RoleSelectionActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun parentDashboardCannotOpenWhileChildModeIsSelected() {
        DeviceRole.select(context, DeviceRole.CHILD)
        val activity = Robolectric.buildActivity(app.eyen.parent.MainActivity::class.java).create().get()
        assertTrue(activity.isFinishing)
        assertEquals(RoleSelectionActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun existingChildPairingSurvivesUnifiedUpgrade() {
        context.getSharedPreferences("eyen_child", Context.MODE_PRIVATE).edit()
            .putString("child_token", "existing-child-token").commit()
        val activity = Robolectric.buildActivity(RoleSelectionActivity::class.java).setup().get()
        assertEquals(DeviceRole.CHILD, DeviceRole.current(context))
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertEquals("existing-child-token", context.getSharedPreferences("eyen_child", Context.MODE_PRIVATE)
            .getString("child_token", null))
    }

    @Test fun replacingOldPairingRequiresConfirmation() {
        DeviceRole.select(context, DeviceRole.CHILD)
        val prefs = context.getSharedPreferences("eyen_child", Context.MODE_PRIVATE)
        prefs.edit().putString("child_token", "old-token")
            .putBoolean("continuous_location_enabled", true).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        activity.findViewById<Button>(R.id.repair_device).performClick()
        assertEquals("old-token", prefs.getString("child_token", null))
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(prefs.contains("child_token"))
        assertFalse(prefs.getBoolean("continuous_location_enabled", false))
        assertEquals(DeviceRole.CHILD, DeviceRole.current(context))
    }

    @Test fun explicitRoleChangeShowsChoicesAndStopsPreviousChildOptIns() {
        DeviceRole.select(context, DeviceRole.CHILD)
        val child = context.getSharedPreferences("eyen_child", Context.MODE_PRIVATE)
        child.edit().putString("child_token", "preserved-token")
            .putBoolean("continuous_location_enabled", true)
            .putBoolean("remote_media_preapproved", true)
            .putBoolean("awaiting_background_location", true).commit()
        val intent = Intent(context, RoleSelectionActivity::class.java)
            .putExtra(RoleSelectionActivity.EXTRA_CHOOSE_ROLE, true)
        val activity = Robolectric.buildActivity(RoleSelectionActivity::class.java, intent).setup().get()
        assertNull(shadowOf(activity).nextStartedActivity)
        activity.findViewById<Button>(R.id.choose_parent).performClick()
        assertEquals(DeviceRole.PARENT, DeviceRole.current(context))
        assertFalse(child.getBoolean("continuous_location_enabled", true))
        assertFalse(child.getBoolean("remote_media_preapproved", true))
        assertFalse(child.getBoolean("awaiting_background_location", true))
        assertEquals("preserved-token", child.getString("child_token", null))
    }
}
