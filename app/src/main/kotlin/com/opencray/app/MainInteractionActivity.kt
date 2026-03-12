package com.opencray.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellTab
import com.opencray.ui.chat.ApprovalDecision
import com.opencray.ui.chat.ChatMode
import com.opencray.ui.chat.ChatScreen

class MainInteractionActivity : LocalizedActivity(), ChatScreen.Listener {
  companion object {
    const val EXTRA_SCENARIO = "com.opencray.app.MainInteractionActivity.extra.SCENARIO"
    const val SCENARIO_DEFAULT_APPROVAL = "default_approval"
    const val SCENARIO_DENIED_POLICY = "denied_policy"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    startActivity(
      Intent(this, AppShellActivity::class.java).apply {
        putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.CHAT.name)
        if (intent.hasExtra(EXTRA_SCENARIO)) {
          putExtra(
            AppShellNavigationExtras.EXTRA_CHAT_SCENARIO,
            intent.getStringExtra(EXTRA_SCENARIO),
          )
        }
      },
    )
    finish()
  }

  // Kept only so legacy androidTests can still compile while this activity acts as a wrapper.
  override fun onQueueVisibilityChanged(isVisible: Boolean) = Unit

  override fun onModeSelected(mode: ChatMode) = Unit

  override fun onApprovalDecision(decision: ApprovalDecision) = Unit

  override fun onResetAgentIdentity() = Unit
}
