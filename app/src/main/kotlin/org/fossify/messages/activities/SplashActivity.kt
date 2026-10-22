package org.fossify.messages.activities

import android.content.Intent
import org.fossify.commons.activities.BaseSplashActivity

class SplashActivity : BaseSplashActivity() {
    override fun initActivity() {
        // The app opens on the mailboxes (folder view), not a merged list.
        startActivity(Intent(this, FolderViewActivity::class.java))
        finish()
    }
}
