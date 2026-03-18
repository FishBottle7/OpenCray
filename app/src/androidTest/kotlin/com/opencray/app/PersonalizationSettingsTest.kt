package com.opencray.app

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.AppShellTab
import com.opencray.app.shell.SettingsSubpage
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.model.SessionRecord
import com.opencray.persistence.store.file.JsonFileMemoryStore
import com.opencray.persistence.store.file.JsonFileSessionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencray.app.R
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class PersonalizationSettingsTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val targetContext = instrumentation.targetContext
  private var launchedActivity: AppShellActivity? = null

  @After
  fun tearDown() {
    launchedActivity?.let { activity ->
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
    }
    AppShellStateStore.fromContext(targetContext).clear()
    TelemetrySettingsStore.fromContext(targetContext).clear()
    clearPersistedPersonalizationState()
    launchedActivity = null
  }

  @Test
  fun personalizationLoadsPersistedSoulStateAndScopedResetsClearOnlyTargetStores() {
    seedPersistedPersonalizationState()
    val expectedTelemetryState = seedTelemetryState()

    launchPersonalization(chatScenario = MainInteractionActivity.SCENARIO_DENIED_POLICY)

    assertActivityTitle(targetContext.getString(R.string.settings_card_personalization))
    assertTextVisible("Personality presets")
    assertTextVisible("Steady guide")
    assertTextVisible("Focused builder")
    assertTextVisible("Warm collaborator")
    assertTextVisible("Custom personality overlay")
    assertTextVisible("Danger zone")
    assertEditTextValue("Optional custom personality label", "Night Shift")
    assertEditTextValue(
      "Add custom tone, goals, and boundaries",
      "Be direct, acknowledge tradeoffs, and keep a calm tone.",
    )
    assertTextVisible("Night Shift")
    assertTextContainingVisible("Custom overlay: Be direct, acknowledge tradeoffs, and keep a calm tone.")

    performCardClickForText("Focused builder")
    assertTextContainingVisible("Base voice: concise, execution-first, and optimized for concrete next steps.")

    setEditTextValue("Optional custom personality label", "Night Shift")
    setEditTextValue(
      "Add custom tone, goals, and boundaries",
      "Be direct, acknowledge tradeoffs, and keep a calm tone.",
    )
    assertTextVisible("Night Shift")
    assertTextContainingVisible("Custom overlay: Be direct, acknowledge tradeoffs, and keep a calm tone.")

    assertButtonEnabled("Reset memory", expected = false)
    setEditTextValue("Type RESET MEMORY", "reset memory")
    assertButtonEnabled("Reset memory", expected = false)
    assertTextVisible("Type RESET MEMORY exactly to enable this reset.")

    setEditTextValue("Type RESET MEMORY", "RESET MEMORY")
    assertButtonEnabled("Reset memory", expected = true)
    performButtonClick("Reset memory")
    assertTextContainingVisible("Cleared the app-local memory and history stores.")
    assertTrue("Expected memory.json to be removed.", !File(personalizationDirectory(), "memory.json").exists())
    assertTrue("Expected session.json to be removed.", !File(personalizationDirectory(), "session.json").exists())
    assertTrue("Expected SOUL.md to stay present after Reset memory.", Files.exists(soulFile()))
    assertTrue("Expected memory store to be empty after Reset memory.", JsonFileMemoryStore(personalizationDirectory()).list().isEmpty())
    assertNull("Expected session store to be cleared after Reset memory.", JsonFileSessionStore(personalizationDirectory()).load())
    assertTrue("Expected workspace soul file to remain after Reset memory.", Files.exists(soulFile()))
    assertTelemetryState(expectedTelemetryState)
    assertEditTextValue("Optional custom personality label", "Night Shift")

    setEditTextValue("Type RESET SOUL", "RESET SOUL")
    assertButtonEnabled("Reset soul", expected = true)
    performButtonClick("Reset soul")
    assertTextContainingVisible("Cleared the local personality and soul profile and reset the editor to defaults.")
    assertTrue("Expected SOUL.md to be removed.", !Files.exists(soulFile()))
    assertEditTextValue("Optional custom personality label", "")
    assertEditTextValue("Add custom tone, goals, and boundaries", "")
    assertTextNotVisible("Night Shift")
    assertTrue("Expected memory store to stay empty after Reset soul.", JsonFileMemoryStore(personalizationDirectory()).list().isEmpty())
    assertNull("Expected session store to stay cleared after Reset soul.", JsonFileSessionStore(personalizationDirectory()).load())
    assertTelemetryState(expectedTelemetryState)
  }

  @Test
  fun personalizationKeepsResetControlsDisabledUntilQueueReturnsIdle() {
    launchPersonalization(chatScenario = MainInteractionActivity.SCENARIO_DEFAULT_APPROVAL)

    assertTextVisible("Queue or session is not idle")
    assertTextContainingVisible(
      "A queued chat action is still waiting for a decision, so both reset controls stay disabled until the shell returns to idle.",
    )
    assertEditTextEnabled("Type RESET MEMORY", expected = false)
    assertEditTextEnabled("Type RESET SOUL", expected = false)
    assertButtonEnabled("Reset memory", expected = false)
    assertButtonEnabled("Reset soul", expected = false)
    assertTextContainingVisible("Disabled until the active queue or session is idle.")
  }

  private fun launchPersonalization(chatScenario: String): AppShellActivity {
    AppShellStateStore.fromContext(targetContext).clear()

    val intent = Intent(targetContext, AppShellActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.SETTINGS.name)
      putExtra(AppShellNavigationExtras.EXTRA_START_SETTINGS_PAGE, SettingsSubpage.PERSONALIZATION.name)
      putExtra(AppShellNavigationExtras.EXTRA_CHAT_SCENARIO, chatScenario)
    }

    return (instrumentation.startActivitySync(intent) as AppShellActivity).also { activity ->
      launchedActivity = activity
      instrumentation.waitForIdleSync()
    }
  }

  private fun assertActivityTitle(expected: String) {
    val actual = runOnActivity { title?.toString().orEmpty() }
    assertEquals("Expected shell title '$expected'.", expected, actual)
  }

  private fun assertTextVisible(text: String) {
    assertTextVisibleMatching(
      queryDescription = "text '$text'",
      textMatcher = { candidate -> candidate == text },
    )
  }

  private fun assertTextContainingVisible(fragment: String) {
    assertTextVisibleMatching(
      queryDescription = "text containing '$fragment'",
      textMatcher = { candidate -> candidate.contains(fragment) },
    )
  }

  private fun assertTextVisibleMatching(
    queryDescription: String,
    textMatcher: (String) -> Boolean,
  ) {
    runOnActivity {
      val textView = requireTextView(queryDescription, textMatcher) { it.isShown }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
    }
    instrumentation.waitForIdleSync()

    val isVisible = runOnActivity {
      val textView = requireTextView(queryDescription, textMatcher) { it.isShown }
      textView.isShown && hasGlobalVisibleBounds(textView)
    }

    assertTrue("Expected $queryDescription to be visible on screen.", isVisible)
  }

  private fun assertTextNotVisible(text: String) {
    val isVisible = runOnActivity {
      val matches = mutableListOf<TextView>()
      collectTextViews(requireActivity().window.decorView.rootView, { candidate -> candidate == text }, matches)
      matches.any { it.isShown && hasGlobalVisibleBounds(it) }
    }

    assertFalse("Expected text '$text' to be absent from the visible screen.", isVisible)
  }

  private fun assertButtonEnabled(
    text: String,
    expected: Boolean,
  ) {
    val actual = runOnActivity {
      requireButton(text) { it.isShown }.isEnabled
    }
    assertEquals("Expected button '$text' enabled=$expected.", expected, actual)
  }

  private fun assertEditTextEnabled(
    hint: String,
    expected: Boolean,
  ) {
    val actual = runOnActivity {
      requireEditText(hint) { it.isShown }.isEnabled
    }
    assertEquals("Expected EditText '$hint' enabled=$expected.", expected, actual)
  }

  private fun performButtonClick(text: String) {
    val clicked = runOnActivity {
      val button = requireButton(text) { it.isShown && it.isEnabled }
      button.requestRectangleOnScreen(viewBounds(button), true)
      button.performClick() || button.callOnClick()
    }
    instrumentation.waitForIdleSync()

    assertTrue("Expected button '$text' click to succeed.", clicked)
  }

  private fun performCardClickForText(text: String) {
    val clicked = runOnActivity {
      val textView = requireTextView("card text '$text'", { candidate -> candidate == text }) { it.isShown }
      textView.requestRectangleOnScreen(viewBounds(textView), true)
      val clickableAncestor = requireClickableAncestor(textView)
      clickableAncestor.performClick() || clickableAncestor.callOnClick()
    }
    instrumentation.waitForIdleSync()

    assertTrue("Expected a card-wide click for '$text' to succeed.", clicked)
  }

  private fun setEditTextValue(
    hint: String,
    value: String,
  ) {
    runOnActivity {
      val input = requireEditText(hint) { it.isShown && it.isEnabled }
      input.requestRectangleOnScreen(viewBounds(input), true)
      input.requestFocus()
      input.setText(value)
      input.setSelection(input.text?.length ?: 0)
    }
    instrumentation.waitForIdleSync()

    val actual = runOnActivity {
      requireEditText(hint) { true }.text?.toString().orEmpty()
    }
    assertEquals("Expected EditText '$hint' to match.", value, actual)
  }

  private fun assertEditTextValue(
    hint: String,
    expected: String,
  ) {
    val actual = runOnActivity {
      requireEditText(hint) { true }.text?.toString().orEmpty()
    }

    assertEquals("Expected EditText '$hint' to match.", expected, actual)
  }

  private fun seedPersistedPersonalizationState() {
    val directory = personalizationDirectory()
    WorkspaceSoulProfileStore().saveSoulProfile(
      AppAgentWorkspace.ensureRootForContext(targetContext),
      WorkspaceSoulProfile(
        presetName = "BUILDER",
        customLabel = "Night Shift",
        customGuidance = "Be direct, acknowledge tradeoffs, and keep a calm tone.",
      ),
    )
    JsonFileMemoryStore(directory).upsert(
      MemoryRecord(
        id = "mem-1",
        content = "Remember the user's last workspace summary.",
        tags = listOf("history"),
        createdAtEpochMs = 1_710_000_000_000L,
        updatedAtEpochMs = 1_710_000_000_000L,
      ),
    )
    JsonFileSessionStore(directory).save(
      SessionRecord(
        sessionId = "session-1",
        agentId = "app-shell-personalization",
        state = mapOf("queue_state" to "idle", "history" to "visible"),
        createdAtEpochMs = 1_710_000_010_000L,
        updatedAtEpochMs = 1_710_000_010_000L,
      ),
    )
  }

  private fun seedTelemetryState(): TelemetryTogglesState {
    val defaults = TelemetryTogglesState.localized(targetContext)
    return defaults.copy(
      telemetry = defaults.telemetry.copy(isChecked = true),
      privacyGuard = defaults.privacyGuard.copy(isChecked = false),
    ).also { seededState ->
      TelemetrySettingsStore.fromContext(targetContext).save(seededState)
    }
  }

  private fun assertTelemetryState(expected: TelemetryTogglesState) {
    val actual = TelemetrySettingsStore.fromContext(targetContext).load(
      TelemetryTogglesState.localized(targetContext),
    )
    assertEquals(expected.telemetry.isChecked, actual.telemetry.isChecked)
    assertEquals(expected.privacyGuard.isChecked, actual.privacyGuard.isChecked)
  }

  private fun personalizationDirectory(): File = PersonalizationLocalStore.directoryForContext(targetContext)

  private fun soulFile() = AppAgentWorkspace.ensureRootForContext(targetContext).resolve("SOUL.md")

  private fun clearPersistedPersonalizationState() {
    val directory = personalizationDirectory()
    JsonFileMemoryStore(directory).clear()
    JsonFileSessionStore(directory).clear()
    Files.deleteIfExists(soulFile())
    if (directory.exists()) {
      directory.delete()
    }
  }

  private fun requireTextView(
    queryDescription: String,
    textMatcher: (String) -> Boolean,
    predicate: (TextView) -> Boolean,
  ): TextView {
    val matches = mutableListOf<TextView>()
    collectTextViews(requireActivity().window.decorView.rootView, textMatcher, matches)
    return matches.firstOrNull(predicate)
      ?: throw AssertionError("Expected to find $queryDescription in the launched activity.")
  }

  private fun collectTextViews(
    root: View,
    textMatcher: (String) -> Boolean,
    matches: MutableList<TextView>,
  ) {
    if (root is TextView && textMatcher(root.text.toString())) {
      matches += root
    }

    if (root is ViewGroup) {
      for (index in 0 until root.childCount) {
        collectTextViews(root.getChildAt(index), textMatcher, matches)
      }
    }
  }

  private fun requireButton(
    text: String,
    predicate: (Button) -> Boolean,
  ): Button {
    val matches = mutableListOf<Button>()
    collectButtons(requireActivity().window.decorView.rootView, text, matches)
    return matches.firstOrNull(predicate)
      ?: throw AssertionError("Expected to find a Button with text '$text' in the launched activity.")
  }

  private fun collectButtons(
    root: View,
    text: String,
    matches: MutableList<Button>,
  ) {
    if (root is Button && root.text.toString() == text) {
      matches += root
    }

    if (root is ViewGroup) {
      for (index in 0 until root.childCount) {
        collectButtons(root.getChildAt(index), text, matches)
      }
    }
  }

  private fun requireEditText(
    hint: String,
    predicate: (EditText) -> Boolean,
  ): EditText {
    val matches = mutableListOf<EditText>()
    collectEditTexts(requireActivity().window.decorView.rootView, hint, matches)
    return matches.firstOrNull(predicate)
      ?: throw AssertionError("Expected to find an EditText with hint '$hint' in the launched activity.")
  }

  private fun collectEditTexts(
    root: View,
    hint: String,
    matches: MutableList<EditText>,
  ) {
    if (root is EditText && root.hint?.toString() == hint) {
      matches += root
    }

    if (root is ViewGroup) {
      for (index in 0 until root.childCount) {
        collectEditTexts(root.getChildAt(index), hint, matches)
      }
    }
  }

  private fun requireClickableAncestor(view: View): View {
    var current: View? = view
    while (current != null) {
      if (current.isClickable && current.isEnabled && current.isShown) {
        return current
      }
      current = current.parent as? View
    }
    throw AssertionError("Expected a clickable ancestor for the requested text view.")
  }

  private fun requireActivity(): AppShellActivity =
    checkNotNull(launchedActivity) { "Expected a launched AppShellActivity." }

  private fun <T> runOnActivity(block: AppShellActivity.() -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync {
      result = runCatching { requireActivity().block() }
    }
    return checkNotNull(result).getOrThrow()
  }

  private fun viewBounds(view: View): Rect = Rect(
    0,
    0,
    view.width.coerceAtLeast(1),
    view.height.coerceAtLeast(1),
  )

  private fun hasGlobalVisibleBounds(view: View): Boolean {
    val rect = Rect()
    return view.getGlobalVisibleRect(rect) && !rect.isEmpty
  }
}
