package com.opencray.ui.files

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.opencray.filesystem.PersistedSafGrantSnapshot
import com.opencray.filesystem.SafAccessRequest
import com.opencray.filesystem.SafAccessState
import com.opencray.filesystem.SafWorkspaceBridge

private const val DEFAULT_WORKSPACE_PICKER_TITLE = "Workspace access"
private const val DEFAULT_WORKSPACE_PICKER_SUBTITLE =
  "Keep SAF grant state visible while host wiring for picker launches lands in a later slice."
private const val DEFAULT_PLACEHOLDER_WORKSPACE_ID = "workspace"

data class WorkspacePickerScreenState(
  val title: String = DEFAULT_WORKSPACE_PICKER_TITLE,
  val subtitle: String = DEFAULT_WORKSPACE_PICKER_SUBTITLE,
  val accessState: SafAccessState = SafAccessState.NotGranted(
    workspaceId = DEFAULT_PLACEHOLDER_WORKSPACE_ID,
    request = SafAccessRequest.RelativePath(""),
  ),
) {
  init {
    require(title.isNotBlank()) { "title must not be blank." }
    require(subtitle.isNotBlank()) { "subtitle must not be blank." }
  }

  companion object {
    fun fromBridge(
      bridge: SafWorkspaceBridge,
      workspaceId: String,
      request: SafAccessRequest,
      title: String = DEFAULT_WORKSPACE_PICKER_TITLE,
      subtitle: String = DEFAULT_WORKSPACE_PICKER_SUBTITLE,
    ): WorkspacePickerScreenState {
      val accessState = when (request) {
        is SafAccessRequest.RelativePath -> bridge.checkRelativePath(workspaceId, request.rawValue)
        is SafAccessRequest.DocumentUri -> bridge.checkDocumentUri(workspaceId, request.rawValue)
      }

      return WorkspacePickerScreenState(
        title = title,
        subtitle = subtitle,
        accessState = accessState,
      )
    }
  }
}

class WorkspacePickerScreen @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
  interface Listener {
    fun onPickWorkspaceRequested(workspaceId: String)

    fun onReauthorizeWorkspaceRequested(workspaceId: String)

    fun onClearGrantRequested(workspaceId: String)
  }

  private val surfaceColor = Color.WHITE
  private val backgroundColor = Color.parseColor("#F4F7FB")
  private val borderColor = Color.parseColor("#D7E1ED")
  private val textPrimary = Color.parseColor("#152538")
  private val textSecondary = Color.parseColor("#5D6B7B")
  private val accentColor = Color.parseColor("#2353B6")
  private val successColor = Color.parseColor("#1F7A44")
  private val warningColor = Color.parseColor("#9A6700")
  private val dangerColor = Color.parseColor("#8E1C1C")

  private var listener: Listener? = null
  private var state: WorkspacePickerScreenState = WorkspacePickerScreenState()

  private val contentContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(24))
  }

  private val headerTitleView = titleText(textSizeSp = 20f)
  private val headerSubtitleView = helperText()

  private val statusCard = sectionCard()
  private val statusBadgeView = badgeText()
  private val statusTitleView = titleText(textSizeSp = 18f)
  private val statusMessageView = bodyText()
  private val workspaceSummaryView = helperText()
  private val requestSummaryView = helperText()

  private val grantSummaryCard = sectionCard()
  private val grantSummaryTitleView = titleText("Granted root summary", 18f)
  private val grantStateView = helperText()
  private val grantRootView = bodyText()
  private val documentRootView = helperText()
  private val treeUriView = helperText()

  private val attentionCard = sectionCard()
  private val attentionTitleView = titleText(textSizeSp = 18f)
  private val attentionMessageView = bodyText()
  private val attentionNoteView = helperText()

  private val actionsNoteView = helperText()
  private val pickWorkspaceButton = actionButton("Pick workspace")
  private val reauthorizeWorkspaceButton = actionButton("Re-authorize workspace")
  private val clearGrantButton = secondaryButton("Clear grant")

  init {
    isFillViewport = true
    setBackgroundColor(backgroundColor)

    addView(
      contentContainer,
      LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )

    contentContainer.addView(buildHeaderCard())
    contentContainer.addView(statusCard, blockParams(topDp = 16))
    contentContainer.addView(grantSummaryCard, blockParams(topDp = 16))
    contentContainer.addView(attentionCard, blockParams(topDp = 16))
    contentContainer.addView(buildActionsCard(), blockParams(topDp = 16))

    setupStatusCard()
    setupGrantSummaryCard()
    setupAttentionCard()
    bindActions()
    submitState(state)
  }

  fun setListener(listener: Listener?) {
    this.listener = listener
  }

  fun submitState(newState: WorkspacePickerScreenState) {
    state = newState
    renderHeader()
    renderStatus()
    renderGrantSummary()
    renderAttentionState()
    renderActions()
  }

  fun snapshotState(): WorkspacePickerScreenState = state

  private fun buildHeaderCard(): View = sectionCard().apply {
    addView(headerTitleView)
    addView(headerSubtitleView, blockParams(topDp = 6))
  }

  private fun buildActionsCard(): View = sectionCard().apply {
    addView(titleText("Workspace actions", 18f))
    addView(
      helperText("Deterministic labels stay stable here so later instrumentation can target the picker flow without guessing."),
      blockParams(topDp = 6),
    )
    addView(actionsNoteView, blockParams(topDp = 12))
    addView(pickWorkspaceButton, blockParams(topDp = 12))
    addView(reauthorizeWorkspaceButton, blockParams(topDp = 10))
    addView(clearGrantButton, blockParams(topDp = 10))
  }

  private fun setupStatusCard() {
    statusCard.addView(statusBadgeView)
    statusCard.addView(statusTitleView, blockParams(topDp = 12))
    statusCard.addView(statusMessageView, blockParams(topDp = 6))
    statusCard.addView(workspaceSummaryView, blockParams(topDp = 10))
    statusCard.addView(requestSummaryView, blockParams(topDp = 4))
  }

  private fun setupGrantSummaryCard() {
    grantSummaryCard.addView(grantSummaryTitleView)
    grantSummaryCard.addView(grantStateView, blockParams(topDp = 8))
    grantSummaryCard.addView(grantRootView, blockParams(topDp = 10))
    grantSummaryCard.addView(documentRootView, blockParams(topDp = 6))
    grantSummaryCard.addView(treeUriView, blockParams(topDp = 6))
  }

  private fun setupAttentionCard() {
    attentionCard.addView(attentionTitleView)
    attentionCard.addView(attentionMessageView, blockParams(topDp = 6))
    attentionCard.addView(attentionNoteView, blockParams(topDp = 10))
  }

  private fun bindActions() {
    pickWorkspaceButton.setOnClickListener {
      listener?.onPickWorkspaceRequested(state.accessState.workspaceId)
    }
    reauthorizeWorkspaceButton.setOnClickListener {
      listener?.onReauthorizeWorkspaceRequested(state.accessState.workspaceId)
    }
    clearGrantButton.setOnClickListener {
      listener?.onClearGrantRequested(state.accessState.workspaceId)
    }
  }

  private fun renderHeader() {
    headerTitleView.text = state.title
    headerSubtitleView.text = state.subtitle
  }

  private fun renderStatus() {
    val accessState = state.accessState
    statusBadgeView.text = statusBadgeLabel(accessState)
    statusBadgeView.background = badgeBackground(statusAccentColor(accessState))
    statusTitleView.text = statusTitle(accessState)
    statusMessageView.text = statusMessage(accessState)
    workspaceSummaryView.text = "Workspace ID: ${accessState.workspaceId}"
    requestSummaryView.text = "Request: ${requestLabel(accessState.request)} • ${requestValue(accessState.request)}"
    statusCard.background = sectionBackground(statusAccentColor(accessState))
  }

  private fun renderGrantSummary() {
    val snapshot = currentSnapshot(state.accessState)
    grantSummaryCard.visibility = if (snapshot == null) View.GONE else View.VISIBLE

    if (snapshot == null) {
      return
    }

    val rootSummary = formatRelativeRoot(snapshot)
    grantStateView.text = buildString {
      append("Saved grant: ")
      append(snapshot.permissionState.name.lowercase())
      if (snapshot.revokedAtEpochMillis != null) {
        append(" • revoked-at=")
        append(snapshot.revokedAtEpochMillis)
      }
    }
    grantRootView.text = "Workspace root: $rootSummary"
    documentRootView.text = "SAF document root: ${snapshot.normalizedRootDocumentId}"
    treeUriView.text = "Tree URI: ${snapshot.treeUri}"
    grantSummaryCard.background = sectionBackground(
      if (snapshot.revokedAtEpochMillis == null) successColor else dangerColor,
    )
  }

  private fun renderAttentionState() {
    when (val accessState = state.accessState) {
      is SafAccessState.Revoked -> {
        val recoverableGrant = accessState.recoverableGrant()
        attentionCard.visibility = View.VISIBLE
        attentionTitleView.text = "Workspace permission was revoked"
        attentionMessageView.text =
          "Android no longer honors the saved SAF grant. Re-authorize workspace to recover access to ${formatRelativeRoot(recoverableGrant)}."
        attentionNoteView.text =
          "The saved grant stays visible below so recovery can target the same root instead of asking the host to guess."
        attentionCard.background = sectionBackground(dangerColor)
      }

      is SafAccessState.OutsideGrantedRoot -> {
        attentionCard.visibility = View.VISIBLE
        attentionTitleView.text = "Requested location is outside the granted root"
        attentionMessageView.text =
          "The stored SAF grant is still active, but this request falls outside ${formatRelativeRoot(accessState.snapshot)}."
        attentionNoteView.text =
          "Pick workspace if this flow needs a broader root, or keep future requests inside the current grant."
        attentionCard.background = sectionBackground(warningColor)
      }

      is SafAccessState.InvalidPath -> {
        attentionCard.visibility = View.VISIBLE
        attentionTitleView.text = "Requested path is invalid"
        attentionMessageView.text =
          "This relative path was rejected before SAF access because it failed local validation with reason ${accessState.reasonCode}."
        attentionNoteView.text =
          "The current grant is still shown below so recovery stays visible even when the request itself is denied."
        attentionCard.background = sectionBackground(dangerColor)
      }

      is SafAccessState.NotGranted,
      is SafAccessState.Granted -> {
        attentionCard.visibility = View.GONE
      }
    }
  }

  private fun renderActions() {
    when (state.accessState) {
      is SafAccessState.NotGranted -> {
        actionsNoteView.text = "No persisted grant is stored yet. Pick workspace to establish the first SAF root."
        pickWorkspaceButton.visibility = View.VISIBLE
        pickWorkspaceButton.isEnabled = true
        reauthorizeWorkspaceButton.visibility = View.GONE
        clearGrantButton.isEnabled = false
      }

      is SafAccessState.Granted -> {
        actionsNoteView.text = "The grant is active. Pick workspace to change roots or clear grant to remove the saved access."
        pickWorkspaceButton.visibility = View.VISIBLE
        pickWorkspaceButton.isEnabled = true
        reauthorizeWorkspaceButton.visibility = View.GONE
        clearGrantButton.isEnabled = true
      }

      is SafAccessState.Revoked -> {
        actionsNoteView.text = "Re-authorize workspace to recover the saved root, or clear grant if this access should be forgotten."
        pickWorkspaceButton.visibility = View.GONE
        reauthorizeWorkspaceButton.visibility = View.VISIBLE
        reauthorizeWorkspaceButton.isEnabled = true
        clearGrantButton.isEnabled = true
      }

      is SafAccessState.OutsideGrantedRoot -> {
        actionsNoteView.text = "The request is denied by the current scope. Pick workspace for a broader root or clear the saved grant."
        pickWorkspaceButton.visibility = View.VISIBLE
        pickWorkspaceButton.isEnabled = true
        reauthorizeWorkspaceButton.visibility = View.GONE
        clearGrantButton.isEnabled = true
      }

      is SafAccessState.InvalidPath -> {
        actionsNoteView.text = "Fix the invalid request first. This surface only keeps the saved grant visible or lets the user clear it."
        pickWorkspaceButton.visibility = View.GONE
        reauthorizeWorkspaceButton.visibility = View.GONE
        clearGrantButton.isEnabled = true
      }
    }
  }

  private fun currentSnapshot(accessState: SafAccessState): PersistedSafGrantSnapshot? = when (accessState) {
    is SafAccessState.Granted -> accessState.snapshot
    is SafAccessState.Revoked -> accessState.snapshot
    is SafAccessState.OutsideGrantedRoot -> accessState.snapshot
    is SafAccessState.InvalidPath -> accessState.snapshot
    is SafAccessState.NotGranted -> null
  }

  private fun statusBadgeLabel(accessState: SafAccessState): String = when (accessState) {
    is SafAccessState.NotGranted -> "NO GRANT"
    is SafAccessState.Granted -> "GRANT ACTIVE"
    is SafAccessState.Revoked -> "RECOVERY NEEDED"
    is SafAccessState.OutsideGrantedRoot -> "OUTSIDE ROOT"
    is SafAccessState.InvalidPath -> "INVALID PATH"
  }

  private fun statusTitle(accessState: SafAccessState): String = when (accessState) {
    is SafAccessState.NotGranted -> "No workspace grant yet"
    is SafAccessState.Granted -> "Workspace grant active"
    is SafAccessState.Revoked -> "Workspace permission needs recovery"
    is SafAccessState.OutsideGrantedRoot -> "Request blocked by the granted root"
    is SafAccessState.InvalidPath -> "Request blocked before SAF access"
  }

  private fun statusMessage(accessState: SafAccessState): String = when (accessState) {
    is SafAccessState.NotGranted ->
      "No persisted SAF grant is stored for this workspace, so the host should prompt for a workspace pick before file access continues."

    is SafAccessState.Granted ->
      "The request sits inside the stored SAF root, so later host wiring can continue without asking for a new permission grant."

    is SafAccessState.Revoked ->
      "A persisted grant record still exists, but Android reported the permission as revoked and the workflow should recover it explicitly."

    is SafAccessState.OutsideGrantedRoot ->
      "The saved grant is valid, but the requested location is outside the currently granted workspace root."

    is SafAccessState.InvalidPath ->
      "The request was rejected as invalid before any SAF access check could succeed."
  }

  private fun statusAccentColor(accessState: SafAccessState): Int = when (accessState) {
    is SafAccessState.NotGranted -> accentColor
    is SafAccessState.Granted -> successColor
    is SafAccessState.Revoked -> dangerColor
    is SafAccessState.OutsideGrantedRoot -> warningColor
    is SafAccessState.InvalidPath -> dangerColor
  }

  private fun requestLabel(request: SafAccessRequest): String = when (request) {
    is SafAccessRequest.RelativePath -> "Relative path"
    is SafAccessRequest.DocumentUri -> "Document URI"
  }

  private fun requestValue(request: SafAccessRequest): String = request.rawValue.ifBlank {
    when (request) {
      is SafAccessRequest.RelativePath -> "(workspace root)"
      is SafAccessRequest.DocumentUri -> "(empty URI)"
    }
  }

  private fun formatRelativeRoot(snapshot: PersistedSafGrantSnapshot): String =
    snapshot.normalizedWorkspaceRelativeRootPath.ifBlank { "entire selected tree" }

  private fun sectionCard(): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = sectionBackground(borderColor)
    setPadding(dp(16), dp(16), dp(16), dp(16))
  }

  private fun titleText(
    value: String,
    textSizeSp: Float,
  ): TextView = TextView(context).apply {
    text = value
    textSize = textSizeSp
    setTextColor(textPrimary)
    setTypeface(typeface, Typeface.BOLD)
  }

  private fun titleText(textSizeSp: Float): TextView = TextView(context).apply {
    textSize = textSizeSp
    setTextColor(textPrimary)
    setTypeface(typeface, Typeface.BOLD)
  }

  private fun bodyText(value: String = ""): TextView = TextView(context).apply {
    text = value
    textSize = 14f
    setTextColor(textPrimary)
    setLineSpacing(0f, 1.12f)
  }

  private fun helperText(value: String = ""): TextView = TextView(context).apply {
    text = value
    textSize = 13f
    setTextColor(textSecondary)
    setLineSpacing(0f, 1.1f)
  }

  private fun badgeText(): TextView = TextView(context).apply {
    textSize = 12f
    setTextColor(Color.WHITE)
    setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(10), dp(6), dp(10), dp(6))
  }

  private fun actionButton(label: String): Button = Button(context).apply {
    text = label
    isAllCaps = false
  }

  private fun secondaryButton(label: String): Button = Button(context).apply {
    text = label
    isAllCaps = false
  }

  private fun sectionBackground(strokeColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(18).toFloat()
    setColor(surfaceColor)
    setStroke(dp(1), strokeColor)
  }

  private fun badgeBackground(fillColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(999).toFloat()
    setColor(fillColor)
  }

  private fun blockParams(
    topDp: Int = 0,
    bottomDp: Int = 0,
  ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
  ).apply {
    topMargin = dp(topDp)
    bottomMargin = dp(bottomDp)
  }

  private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

// Learning: Rendering directly from SafAccessState keeps later host wiring simple because the UI never has to invent parallel permission state enums.
// Issue: This slice exposes UI actions only; the actual SAF picker launcher and persisted-permission handoff still need host integration later.
