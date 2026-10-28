#!/usr/bin/env python3
"""Dragon Messages 1.10.7 edits: patched Commons dep, version bump,
Fossify dedication row in Settings. Anchor-asserted; aborts on any miss."""
import re, sys
from pathlib import Path

ROOT = Path("/home/ben/messages-app")

def replace(path, old, new, count=1):
    p = ROOT / path
    text = p.read_text()
    assert old in text, f"ANCHOR MISS in {path}: {old[:80]!r}"
    assert text.count(old) == count, f"ANCHOR COUNT {text.count(old)} != {count} in {path}: {old[:80]!r}"
    p.write_text(text.replace(old, new))
    print(f"edited {path}")

# 1. Commons dep: official 7.1.3 -> patched 7.1.3-dragon1
replace("gradle/libs.versions.toml", 'commons = "7.1.3"', 'commons = "7.1.3-dragon1"')

# 2. Version bump 1.10.6 (31) -> 1.10.7 (32)
props = ROOT / "gradle.properties"
text = props.read_text()
m_name = re.search(r"^VERSION_NAME=(.*)$", text, re.M)
m_code = re.search(r"^VERSION_CODE=(.*)$", text, re.M)
assert m_name and m_name.group(1).strip() == "1.10.6", f"VERSION_NAME is {m_name.group(1) if m_name else None}"
assert m_code and m_code.group(1).strip() == "31", f"VERSION_CODE is {m_code.group(1) if m_code else None}"
props.write_text(text.replace("VERSION_NAME=1.10.6", "VERSION_NAME=1.10.7").replace("VERSION_CODE=31", "VERSION_CODE=32"))
print("edited gradle.properties (1.10.7 / 32)")

# 3. Strings for the dedication row, after the tip strings
replace("app/src/main/res/values/strings.xml",
        '    <string name="tip_link_subtitle">Voluntary tip — opens in your browser</string>',
        '    <string name="tip_link_subtitle">Voluntary tip — opens in your browser</string>\n'
        '    <string name="fossify_credit_title">Built on Fossify Messages</string>\n'
        '    <string name="fossify_credit_subtitle">Dragon Messages was built on Fossify Messages, free and open-source software. If you like it, consider visiting fossify.org.</string>')

# 4. Layout: dedication holder after the tip holder
replace("app/src/main/res/layout/activity_settings.xml",
        '''                    android:text="@string/tip_link_subtitle" />

            </RelativeLayout>''',
        '''                    android:text="@string/tip_link_subtitle" />

            </RelativeLayout>

            <!-- Fossify credit (1.10.7): a quiet row like the tip row,
                 crediting the FOSS codebase this app is built on. -->
            <RelativeLayout
                android:id="@+id/settings_fossify_credit_holder"
                style="@style/SettingsHolderTextViewStyle"
                android:layout_width="match_parent"
                android:layout_height="wrap_content">

                <org.fossify.commons.views.MyTextView
                    android:id="@+id/settings_fossify_credit"
                    style="@style/SettingsTextLabelStyle"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/fossify_credit_title" />

                <org.fossify.commons.views.MyTextView
                    android:id="@+id/settings_fossify_credit_subtitle"
                    style="@style/SettingsTextValueStyle"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_below="@id/settings_fossify_credit"
                    android:text="@string/fossify_credit_subtitle" />

            </RelativeLayout>''')

# 5. SettingsActivity: call + function, mirroring setupTipLink
replace("app/src/main/kotlin/org/fossify/messages/activities/SettingsActivity.kt",
        "        setupTipLink()\n",
        "        setupTipLink()\n        setupFossifyCredit()\n")
replace("app/src/main/kotlin/org/fossify/messages/activities/SettingsActivity.kt",
        '''    /** The tip row: visible only once Ben supplies a destination (see [TipLink]). */
    private fun setupTipLink() = binding.apply {
        settingsTipLinkHolder.beVisibleIf(TipLink.TIP_URL.isNotBlank())
        settingsTipLinkHolder.setOnClickListener {
            if (TipLink.TIP_URL.isNotBlank()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TipLink.TIP_URL))
                startActivity(intent)
            }
        }
    }''',
        '''    /** The tip row: visible only once Ben supplies a destination (see [TipLink]). */
    private fun setupTipLink() = binding.apply {
        settingsTipLinkHolder.beVisibleIf(TipLink.TIP_URL.isNotBlank())
        settingsTipLinkHolder.setOnClickListener {
            if (TipLink.TIP_URL.isNotBlank()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TipLink.TIP_URL))
                startActivity(intent)
            }
        }
    }

    /** Fossify credit row (1.10.7): always visible, opens fossify.org. */
    private fun setupFossifyCredit() = binding.apply {
        settingsFossifyCreditHolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FossifyCredit.FOSSIFY_URL))
            startActivity(intent)
        }
    }''')

print("ALL 1.10.7 EDITS OK")
