package com.opencray.app

import android.app.Activity
import android.os.Bundle
import com.opencray.ui.skills.SkillEditorViewModel
import com.opencray.ui.skills.SkillsScreen

class SkillsManagementActivity : Activity() {
  private lateinit var skillsScreen: SkillsScreen

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    title = "Skills Management"
    skillsScreen = SkillsScreen(
      context = this,
      viewModel = SkillEditorViewModel(),
    )
    setContentView(skillsScreen)
  }
}
