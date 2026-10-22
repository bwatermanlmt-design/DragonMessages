package org.fossify.messages.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import com.google.android.material.tabs.TabLayout
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.adapters.QuarantinedConversationsAdapter
import org.fossify.messages.databinding.ActivityQuarantinedConversationsBinding
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.helpers.runRetroactiveFiling
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Events
import org.fossify.messages.models.Mailbox
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * The review queue: conversations the filter filed into Quarantine, with a
 * second tab for Spam. Per-conversation actions (release, block, delete)
 * live in the adapter's action mode.
 */
class QuarantinedConversationsActivity : SimpleActivity() {
    private var bus: EventBus? = null
    private val binding by viewBinding(ActivityQuarantinedConversationsBinding::inflate)
    private var currentMailbox = Mailbox.QUARANTINE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()
        setupTabs()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.conversationsList))
        setupMaterialScrollListener(
            scrollingView = binding.conversationsList,
            topAppBar = binding.quarantineAppbar
        )

        loadConversations()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.quarantineAppbar, NavigationIcon.Arrow)
        styleTabs()
        loadConversations()
    }

    override fun onDestroy() {
        super.onDestroy()
        bus?.unregister(this)
    }

    private fun setupOptionsMenu() {
        binding.quarantineToolbar.inflateMenu(R.menu.quarantine_menu)
        binding.quarantineToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.view_filter_log -> {
                    startActivity(Intent(this, FilterAuditLogActivity::class.java))
                    true
                }

                R.id.file_existing_conversations -> {
                    runRetroactiveFiling()
                    true
                }

                else -> false
            }
        }
    }

    private fun setupTabs() {
        binding.quarantineTabs.apply {
            addTab(newTab().setText(R.string.quarantine))
            addTab(newTab().setText(R.string.spam))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    currentMailbox = if (tab.position == 0) Mailbox.QUARANTINE else Mailbox.SPAM
                    binding.conversationsList.adapter = null
                    loadConversations()
                }

                override fun onTabUnselected(tab: TabLayout.Tab) {}

                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
        }
    }

    private fun styleTabs() {
        val primaryColor = getProperPrimaryColor()
        val contrastColor = primaryColor.getContrastColor()
        binding.quarantineTabs.apply {
            setBackgroundColor(primaryColor)
            tabTextColors = ColorStateList.valueOf(contrastColor)
            setSelectedTabIndicatorColor(contrastColor)
        }
    }

    private fun loadConversations() {
        ensureBackgroundThread {
            val conversations = try {
                conversationsDB.getByMailbox(currentMailbox).toMutableList() as ArrayList<Conversation>
            } catch (e: Exception) {
                ArrayList()
            }

            runOnUiThread {
                setupConversations(conversations)
            }
        }

        bus = EventBus.getDefault()
        try {
            bus!!.register(this)
        } catch (ignored: Exception) {
        }
    }

    private fun getOrCreateConversationsAdapter(): QuarantinedConversationsAdapter {
        var currAdapter = binding.conversationsList.adapter
        if (currAdapter == null) {
            hideKeyboard()
            currAdapter = QuarantinedConversationsAdapter(
                activity = this,
                recyclerView = binding.conversationsList,
                mailbox = currentMailbox,
                onRefresh = { notifyDatasetChanged() },
                itemClick = { handleConversationClick(it) }
            )

            binding.conversationsList.adapter = currAdapter
            if (areSystemAnimationsEnabled) {
                binding.conversationsList.scheduleLayoutAnimation()
            }
        }
        return currAdapter as QuarantinedConversationsAdapter
    }

    private fun setupConversations(conversations: ArrayList<Conversation>) {
        val sortedConversations = conversations.sortedWith(
            compareByDescending<Conversation> { config.pinnedConversations.contains(it.threadId.toString()) }
                .thenByDescending { it.date }
        ).toMutableList() as ArrayList<Conversation>

        showOrHidePlaceholder(conversations.isEmpty())

        try {
            getOrCreateConversationsAdapter().apply {
                updateConversations(sortedConversations)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        binding.conversationsFastscroller.beGoneIf(show)
        binding.noConversationsPlaceholder.beVisibleIf(show)
        binding.noConversationsPlaceholder.setTextColor(getProperTextColor())
        binding.noConversationsPlaceholder.text = getString(
            if (currentMailbox == Mailbox.SPAM) {
                R.string.no_spam_conversations
            } else {
                R.string.no_quarantined_conversations
            }
        )
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun notifyDatasetChanged() {
        getOrCreateConversationsAdapter().notifyDataSetChanged()
    }

    private fun handleConversationClick(any: Any) {
        Intent(this, ThreadActivity::class.java).apply {
            val conversation = any as Conversation
            putExtra(THREAD_ID, conversation.threadId)
            putExtra(THREAD_TITLE, conversation.title)
            startActivity(this)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshConversations(@Suppress("unused") event: Events.RefreshConversations) {
        loadConversations()
    }
}
