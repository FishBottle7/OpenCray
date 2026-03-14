package com.opencray.app.facade.personalization

import android.content.Context
import com.opencray.app.AppLanguage
import com.opencray.app.LocaleSettingsStore
import com.opencray.app.OpenCrayLocaleManager
import com.opencray.app.PersonalizationLocalStore
import com.opencray.app.PersonalizationPreset
import com.opencray.app.PersonalizationResetPreview
import org.opencray.app.R

enum class PersonalizationResetScope(
  val wireValue: String,
) {
  MEMORY("memory"),
  SOUL("soul"),
  ;

  companion object {
    fun fromWireValue(rawValue: String?): PersonalizationResetScope = entries.firstOrNull { scope ->
      scope.wireValue.equals(rawValue?.trim(), ignoreCase = true) ||
        scope.name.equals(rawValue?.trim(), ignoreCase = true)
    } ?: MEMORY
  }
}

data class PersonalizationPresetSnapshot(
  val id: String,
  val title: String,
  val summary: String,
  val voice: String,
  val status: String,
  val isSelected: Boolean,
)

data class PersonalizationResetActionSnapshot(
  val scope: PersonalizationResetScope,
  val title: String,
  val scopeBody: String,
  val retainBody: String,
  val confirmationToken: String,
  val inputHint: String,
  val disabledGuidance: String,
  val typeExactGuidance: String,
  val armedGuidance: String,
  val isInputEnabled: Boolean,
)

data class PersonalizationLanguageOptionSnapshot(
  val id: String,
  val title: String,
  val isSelected: Boolean,
)

data class PersonalizationConfigSnapshot(
  val title: String,
  val subtitle: String,
  val introTitle: String,
  val introBody: String,
  val introHelper: String,
  val presetsTitle: String,
  val presetsHelper: String,
  val presets: List<PersonalizationPresetSnapshot>,
  val selectedPresetId: String,
  val customOverlayTitle: String,
  val customOverlayHelper: String,
  val customLabelHint: String,
  val customLabelHelper: String,
  val customGuidanceHint: String,
  val customGuidanceHelper: String,
  val customLabel: String,
  val customGuidance: String,
  val behaviorDefaultsTitle: String,
  val appLanguageTitle: String,
  val appLanguageOptions: List<PersonalizationLanguageOptionSnapshot>,
  val selectedAppLanguageId: String,
  val livePreviewTitle: String,
  val livePreviewName: String,
  val livePreviewSummary: String,
  val queueTitle: String,
  val queueBody: String,
  val queueIsIdle: Boolean,
  val lastResetTitle: String,
  val lastResetMessage: String?,
  val resetActions: List<PersonalizationResetActionSnapshot>,
)

data class SavePersonalizationConfigRequest(
  val presetId: String,
  val customLabel: String,
  val customGuidance: String,
)

interface PersonalizationFacade {
  fun load(): PersonalizationConfigSnapshot

  fun save(request: SavePersonalizationConfigRequest): PersonalizationConfigSnapshot

  fun setAppLanguage(languageId: String): PersonalizationConfigSnapshot

  fun reset(scope: PersonalizationResetScope): PersonalizationConfigSnapshot
}

internal object EmptyPersonalizationFacade : PersonalizationFacade {
  override fun load(): PersonalizationConfigSnapshot = PersonalizationConfigSnapshot(
    title = "",
    subtitle = "",
    introTitle = "",
    introBody = "",
    introHelper = "",
    presetsTitle = "",
    presetsHelper = "",
    presets = emptyList(),
    selectedPresetId = PersonalizationPreset.STEADY.name.lowercase(),
    customOverlayTitle = "",
    customOverlayHelper = "",
    customLabelHint = "",
    customLabelHelper = "",
    customGuidanceHint = "",
    customGuidanceHelper = "",
    customLabel = "",
    customGuidance = "",
    behaviorDefaultsTitle = "",
    appLanguageTitle = "",
    appLanguageOptions = emptyList(),
    selectedAppLanguageId = AppLanguage.default.tag,
    livePreviewTitle = "",
    livePreviewName = "",
    livePreviewSummary = "",
    queueTitle = "",
    queueBody = "",
    queueIsIdle = true,
    lastResetTitle = "",
    lastResetMessage = null,
    resetActions = emptyList(),
  )

  override fun save(request: SavePersonalizationConfigRequest): PersonalizationConfigSnapshot = load()

  override fun setAppLanguage(languageId: String): PersonalizationConfigSnapshot = load()

  override fun reset(scope: PersonalizationResetScope): PersonalizationConfigSnapshot = load()
}

internal class LocalPersonalizationFacade private constructor(
  private val context: Context,
  private val store: PersonalizationLocalStore,
  private val queueIdleProvider: () -> Boolean,
) : PersonalizationFacade {
  private var latestResetPreview: PersonalizationResetPreview = PersonalizationResetPreview.NONE

  override fun load(): PersonalizationConfigSnapshot = snapshotFor(
    profile = store.loadSoulProfile().orDefault(),
  )

  override fun save(request: SavePersonalizationConfigRequest): PersonalizationConfigSnapshot {
    val preset = PersonalizationPreset.fromRaw(request.presetId)
    val profile = PersonalizationLocalStore.SoulProfile(
      presetName = preset.name,
      customLabel = request.customLabel.trim(),
      customGuidance = request.customGuidance.trim(),
    )
    store.saveSoulProfile(profile)
    return snapshotFor(profile = profile)
  }

  override fun setAppLanguage(languageId: String): PersonalizationConfigSnapshot {
    LocaleSettingsStore.fromContext(context).saveLanguage(AppLanguage.fromRaw(languageId))
    return snapshotFor(profile = store.loadSoulProfile().orDefault())
  }

  override fun reset(scope: PersonalizationResetScope): PersonalizationConfigSnapshot {
    require(queueIdleProvider()) {
      context.getString(R.string.personalization_reset_guidance_disabled)
    }
    when (scope) {
      PersonalizationResetScope.MEMORY -> {
        store.clearMemoryAndHistory()
        latestResetPreview = PersonalizationResetPreview.MEMORY
      }

      PersonalizationResetScope.SOUL -> {
        store.clearSoulProfile()
        latestResetPreview = PersonalizationResetPreview.SOUL
      }
    }
    return snapshotFor(profile = store.loadSoulProfile().orDefault())
  }

  private fun snapshotFor(
    profile: PersonalizationLocalStore.SoulProfile,
  ): PersonalizationConfigSnapshot {
    val preset = PersonalizationPreset.fromRaw(profile.presetName)
    val appLanguage = LocaleSettingsStore.fromContext(context).loadLanguage()
    val queueIsIdle = queueIdleProvider()
    val selectedPreset = preset.snapshot(isSelected = true)
    val livePreviewName = profile.customLabel.ifBlank { selectedPreset.title }
    val livePreviewSummary = if (profile.customGuidance.isBlank()) {
      context.getString(
        R.string.personalization_profile_summary_default,
        selectedPreset.voice,
      )
    } else {
      context.getString(
        R.string.personalization_profile_summary_custom,
        selectedPreset.voice,
        profile.customGuidance,
      )
    }
    return PersonalizationConfigSnapshot(
      title = context.getString(R.string.settings_card_personalization),
      subtitle = context.getString(R.string.settings_personalization_subtitle),
      introTitle = context.getString(R.string.personalization_shape_title),
      introBody = context.getString(R.string.personalization_shape_body),
      introHelper = context.getString(R.string.personalization_shape_helper),
      presetsTitle = context.getString(R.string.personalization_presets_title),
      presetsHelper = context.getString(R.string.personalization_presets_helper),
      presets = PersonalizationPreset.entries.map { candidate ->
        candidate.snapshot(isSelected = candidate == preset)
      },
      selectedPresetId = preset.name.lowercase(),
      customOverlayTitle = context.getString(R.string.personalization_custom_overlay_title),
      customOverlayHelper = context.getString(R.string.personalization_custom_overlay_helper),
      customLabelHint = context.getString(R.string.personalization_custom_label_hint),
      customLabelHelper = context.getString(R.string.personalization_custom_label_helper),
      customGuidanceHint = context.getString(R.string.personalization_custom_guidance_hint),
      customGuidanceHelper = context.getString(R.string.personalization_custom_guidance_helper),
      customLabel = profile.customLabel,
      customGuidance = profile.customGuidance,
      behaviorDefaultsTitle = context.getString(R.string.personalization_screen_behavior_defaults),
      appLanguageTitle = context.getString(R.string.personalization_screen_app_language_title),
      appLanguageOptions = AppLanguage.entries.map { candidate ->
        candidate.snapshot(isSelected = candidate == appLanguage)
      },
      selectedAppLanguageId = appLanguage.tag,
      livePreviewTitle = context.getString(R.string.personalization_live_preview_title),
      livePreviewName = livePreviewName,
      livePreviewSummary = livePreviewSummary,
      queueTitle = context.getString(
        if (queueIsIdle) {
          R.string.personalization_queue_idle_title
        } else {
          R.string.personalization_queue_busy_title
        },
      ),
      queueBody = context.getString(
        if (queueIsIdle) {
          R.string.personalization_queue_idle_body
        } else {
          R.string.personalization_queue_busy_body
        },
      ),
      queueIsIdle = queueIsIdle,
      lastResetTitle = context.getString(R.string.personalization_last_staged_reset_title),
      lastResetMessage = latestResetPreview.messageOrNull(),
      resetActions = listOf(
        resetActionSnapshot(
          scope = PersonalizationResetScope.MEMORY,
          titleResId = R.string.personalization_reset_memory_title,
          scopeResId = R.string.personalization_reset_memory_scope,
          retainResId = R.string.personalization_reset_memory_retain,
          queueIsIdle = queueIsIdle,
        ),
        resetActionSnapshot(
          scope = PersonalizationResetScope.SOUL,
          titleResId = R.string.personalization_reset_soul_title,
          scopeResId = R.string.personalization_reset_soul_scope,
          retainResId = R.string.personalization_reset_soul_retain,
          queueIsIdle = queueIsIdle,
        ),
      ),
    )
  }

  private fun resetActionSnapshot(
    scope: PersonalizationResetScope,
    titleResId: Int,
    scopeResId: Int,
    retainResId: Int,
    queueIsIdle: Boolean,
  ): PersonalizationResetActionSnapshot {
    val token = context.getString(
      when (scope) {
        PersonalizationResetScope.MEMORY -> R.string.reset_memory_phrase
        PersonalizationResetScope.SOUL -> R.string.reset_soul_phrase
      },
    )
    return PersonalizationResetActionSnapshot(
      scope = scope,
      title = context.getString(titleResId),
      scopeBody = context.getString(scopeResId),
      retainBody = context.getString(retainResId),
      confirmationToken = token,
      inputHint = context.getString(R.string.personalization_reset_input_hint, token),
      disabledGuidance = context.getString(R.string.personalization_reset_guidance_disabled),
      typeExactGuidance = context.getString(
        R.string.personalization_reset_guidance_type_exact,
        token,
      ),
      armedGuidance = context.getString(R.string.personalization_reset_guidance_armed),
      isInputEnabled = queueIsIdle,
    )
  }

  private fun PersonalizationPreset.snapshot(isSelected: Boolean): PersonalizationPresetSnapshot =
    PersonalizationPresetSnapshot(
      id = name.lowercase(),
      title = context.getString(titleResId()),
      summary = context.getString(summaryResId()),
      voice = context.getString(voiceResId()),
      status = context.getString(
        if (isSelected) {
          R.string.personalization_preset_status_selected
        } else {
          R.string.personalization_preset_status_available
        },
      ),
      isSelected = isSelected,
    )

  private fun PersonalizationPreset.titleResId(): Int = when (this) {
    PersonalizationPreset.STEADY -> R.string.personalization_preset_steady_title
    PersonalizationPreset.BUILDER -> R.string.personalization_preset_builder_title
    PersonalizationPreset.WARM -> R.string.personalization_preset_warm_title
  }

  private fun PersonalizationPreset.summaryResId(): Int = when (this) {
    PersonalizationPreset.STEADY -> R.string.personalization_preset_steady_summary
    PersonalizationPreset.BUILDER -> R.string.personalization_preset_builder_summary
    PersonalizationPreset.WARM -> R.string.personalization_preset_warm_summary
  }

  private fun PersonalizationPreset.voiceResId(): Int = when (this) {
    PersonalizationPreset.STEADY -> R.string.personalization_preset_steady_voice
    PersonalizationPreset.BUILDER -> R.string.personalization_preset_builder_voice
    PersonalizationPreset.WARM -> R.string.personalization_preset_warm_voice
  }

  private fun PersonalizationResetPreview.messageOrNull(): String? = when (this) {
    PersonalizationResetPreview.NONE -> null
    PersonalizationResetPreview.MEMORY -> context.getString(R.string.personalization_reset_preview_memory)
    PersonalizationResetPreview.SOUL -> context.getString(R.string.personalization_reset_preview_soul)
  }

  private fun AppLanguage.snapshot(isSelected: Boolean): PersonalizationLanguageOptionSnapshot =
    PersonalizationLanguageOptionSnapshot(
      id = tag,
      title = context.getString(titleResId()),
      isSelected = isSelected,
    )

  private fun AppLanguage.titleResId(): Int = when (this) {
    AppLanguage.ENGLISH -> R.string.settings_language_english
    AppLanguage.SIMPLIFIED_CHINESE -> R.string.settings_language_simplified_chinese
  }

  private fun PersonalizationLocalStore.SoulProfile?.orDefault(): PersonalizationLocalStore.SoulProfile =
    this ?: PersonalizationLocalStore.SoulProfile(
      presetName = PersonalizationPreset.STEADY.name,
      customLabel = "",
      customGuidance = "",
    )

  companion object {
    fun fromContext(context: Context): PersonalizationFacade = LocalPersonalizationFacade(
      context = OpenCrayLocaleManager.wrap(context.applicationContext),
      store = PersonalizationLocalStore.fromContext(context.applicationContext),
      queueIdleProvider = { true },
    )

    internal fun createForTest(
      context: Context,
      store: PersonalizationLocalStore,
      queueIdleProvider: () -> Boolean = { true },
    ): PersonalizationFacade = LocalPersonalizationFacade(
      context = context,
      store = store,
      queueIdleProvider = queueIdleProvider,
    )
  }
}
