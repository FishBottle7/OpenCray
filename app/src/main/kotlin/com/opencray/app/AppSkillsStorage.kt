package com.opencray.app

import android.content.Context
import java.io.File

object AppSkillsStorage {
  private const val MANAGED_SKILLS_DIRECTORY = "skills"
  private const val CATALOG_SKILLS_DIRECTORY = "skills-catalog"

  fun managedSkillsRootForContext(context: Context): File =
    File(context.applicationContext.filesDir, MANAGED_SKILLS_DIRECTORY)

  fun catalogSkillsRootForContext(context: Context): File =
    File(context.applicationContext.filesDir, CATALOG_SKILLS_DIRECTORY)
}
