package com.opencray.app

import android.content.Context
import android.content.res.AssetManager
import java.io.File

class BuiltinSkillsSeeder private constructor(
  private val context: Context,
  private val assets: AssetManager,
) {
  fun seedBundledSkillsIfNeeded() {
    val managedRoot = AppSkillsStorage.managedSkillsRootForContext(context)
    if (!managedRoot.exists()) {
      managedRoot.mkdirs()
    }
    BUILTIN_SKILL_NAMES.forEach { skillName ->
      val targetDirectory = File(managedRoot, skillName)
      val skillFile = File(targetDirectory, "SKILL.md")
      if (skillFile.exists()) {
        return@forEach
      }
      if (targetDirectory.exists()) {
        targetDirectory.deleteRecursively()
      }
      copyAssetDirectory(
        assetPath = "$BUILTIN_SKILLS_ASSET_ROOT/$skillName",
        targetDirectory = targetDirectory,
      )
    }
  }

  private fun copyAssetDirectory(
    assetPath: String,
    targetDirectory: File,
  ) {
    val children = assets.list(assetPath).orEmpty()
    if (children.isEmpty()) {
      targetDirectory.parentFile?.mkdirs()
      assets.open(assetPath).use { input ->
        targetDirectory.outputStream().use(input::copyTo)
      }
      return
    }

    targetDirectory.mkdirs()
    children.forEach { child ->
      copyAssetDirectory(
        assetPath = "$assetPath/$child",
        targetDirectory = File(targetDirectory, child),
      )
    }
  }

  companion object {
    private const val BUILTIN_SKILLS_ASSET_ROOT = "builtin-skills"
    private val BUILTIN_SKILL_NAMES = listOf(
      "find-skills",
      "skill-creator",
    )

    fun fromContext(context: Context): BuiltinSkillsSeeder = BuiltinSkillsSeeder(
      context = context.applicationContext,
      assets = context.applicationContext.assets,
    )
  }
}
