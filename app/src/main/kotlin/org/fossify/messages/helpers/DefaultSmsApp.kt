package org.fossify.messages.helpers

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.fossify.commons.helpers.isQPlus

/**
 * The default-SMS role, shared by the Locker flows. Only the default SMS
 * app may write to Android's shared message database, so locking (which
 * deletes from the provider) and unlocking (which writes back to it) both
 * require the role. The Locker list itself needs no role: it reads
 * Dragon's own encrypted store, never the provider.
 */

/**
 * Request code for the system "make default SMS app" dialog. MainActivity
 * already handles this code (re-checks permissions on grant, finishes on
 * decline); other activities ignore the result, and the user retries the
 * move after granting.
 */
const val REQUEST_DEFAULT_SMS_APP = 1

fun Context.isDefaultSmsApp(): Boolean {
    return if (isQPlus()) {
        val roleManager = getSystemService(RoleManager::class.java)
        roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
    } else {
        Telephony.Sms.getDefaultSmsPackage(this) == packageName
    }
}

fun Activity.requestDefaultSmsApp() {
    if (isQPlus()) {
        val roleManager = getSystemService(RoleManager::class.java)
        val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_SMS)
        if (intent != null) {
            startActivityForResult(intent, REQUEST_DEFAULT_SMS_APP)
        }
    } else {
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
        intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        startActivityForResult(intent, REQUEST_DEFAULT_SMS_APP)
    }
}
