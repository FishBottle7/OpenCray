package com.opencray.ui.timeline

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.opencray.ui.design.OpenCraySurfaceTone
import com.opencray.ui.design.OpenCrayUiTokens
import com.opencray.ui.design.ocBodyText
import com.opencray.ui.design.ocCardBackground
import com.opencray.ui.design.ocCardTitleText
import com.opencray.ui.design.ocDp
import com.opencray.ui.design.ocLinearBlockParams
import com.opencray.ui.design.ocMetaText
import com.opencray.ui.design.ocPillBackground
import com.opencray.ui.design.ocSurfaceBackground

data class ActionTimelineItem(
  val sequenceNumber: Int,
  val operationLabel: String,
  val policyDecision: ActionPolicyDecision,
  val resultStatus: ActionResultStatus,
  val reasonText: String,
  val approvalState: ActionApprovalState,
) {
  init {
    require(sequenceNumber >= 0) { "sequenceNumber must be non-negative." }
    require(operationLabel.isNotBlank()) { "operationLabel must not be blank." }
    require(reasonText.isNotBlank()) { "reasonText must not be blank." }
  }
}

enum class ActionPolicyDecision(
  val displayName: String,
) {
  ALLOW("Allow"),
  ASK("Ask"),
  DENY("Deny"),
}

enum class ActionResultStatus(
  val displayName: String,
) {
  SUCCESS("Success"),
  FAILED("Failed"),
  TIMEOUT("Timeout"),
  CANCELLED("Cancelled"),
}

enum class ActionApprovalState(
  val displayName: String,
) {
  NOT_REQUIRED("No approval"),
  REQUIRED("Approval required"),
  GRANTED("Approval granted"),
}

class ActionTimeline @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
  companion object {
    const val DEFAULT_EMPTY_MESSAGE = "No action history yet. Policy decisions and results will appear here."
  }

  private val surfaceColor = OpenCrayUiTokens.surface
  private val borderColor = OpenCrayUiTokens.border
  private val textPrimary = OpenCrayUiTokens.textPrimary
  private val textSecondary = OpenCrayUiTokens.textSecondary
  private val accentColor = OpenCrayUiTokens.primary
  private val successColor = OpenCrayUiTokens.success
  private val warningColor = OpenCrayUiTokens.warning
  private val dangerColor = OpenCrayUiTokens.danger
  private val mutedColor = OpenCrayUiTokens.textTertiary

  private var items: List<ActionTimelineItem> = emptyList()
  private var emptyStateMessage: String = DEFAULT_EMPTY_MESSAGE

  private val listContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }

  init {
    orientation = LinearLayout.VERTICAL
    addView(
      listContainer,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
    render()
  }

  fun submitItems(timelineItems: List<ActionTimelineItem>) {
    items = timelineItems.sortedBy(ActionTimelineItem::sequenceNumber)
    render()
  }

  fun setEmptyStateMessage(message: String) {
    emptyStateMessage = message.trim().ifBlank { DEFAULT_EMPTY_MESSAGE }
    if (items.isEmpty()) {
      render()
    }
  }

  fun clear() {
    submitItems(emptyList())
  }

  fun snapshotItems(): List<ActionTimelineItem> = items.toList()

  private fun render() {
    listContainer.removeAllViews()

    if (items.isEmpty()) {
      listContainer.addView(emptyStateCard())
      return
    }

    items.forEachIndexed { index, item ->
      listContainer.addView(
        itemCard(item),
        blockParams(bottomDp = if (index == items.lastIndex) 0 else 12),
      )
    }
  }

  private fun emptyStateCard(): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = cardBackground()
    setPadding(dp(16), dp(16), dp(16), dp(16))
    addView(titleText("No timeline entries", textSizeSp = 18f))
    addView(
      helperText(emptyStateMessage).apply {
        setLineSpacing(0f, 1.12f)
      },
      blockParams(topDp = 6),
    )
  }

  private fun itemCard(item: ActionTimelineItem): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    background = cardBackground()

    addView(
      View(context).apply {
        background = railBackground(policyPalette(item.policyDecision).fillColor)
      },
      LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT),
    )

    addView(
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))

        addView(operationHeader(item))
        addView(chipRows(item), blockParams(topDp = 10))
        addView(
          bodyText("Reason: ${item.reasonText}").apply {
            setLineSpacing(0f, 1.14f)
          },
          blockParams(topDp = 10),
        )
      },
      LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
      ),
    )
  }

  private fun operationHeader(item: ActionTimelineItem): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL

    addView(sequenceBadge(item.sequenceNumber))
    addView(
      titleText(item.operationLabel, textSizeSp = 17f).apply {
        setLineSpacing(0f, 1.08f)
      },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = dp(10)
      },
    )
  }

  private fun chipRows(item: ActionTimelineItem): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL

    addView(
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(chip("Policy ${item.policyDecision.displayName}", policyPalette(item.policyDecision)))
        addView(
          chip("Result ${item.resultStatus.displayName}", resultPalette(item.resultStatus)),
          chipParams(startDp = 8),
        )
      },
    )

    addView(
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(chip(item.approvalState.displayName, approvalPalette(item.approvalState)))
      },
      blockParams(topDp = 8),
    )
  }

  private fun sequenceBadge(sequenceNumber: Int): TextView = TextView(context).apply {
    text = sequenceNumber.toString().padStart(2, '0')
    textSize = 12f
    gravity = Gravity.CENTER
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(accentColor)
    background = chipBackground(
      fillColor = Color.parseColor("#EAF2FF"),
      borderColor = Color.parseColor("#BED4FF"),
      cornerDp = 10,
    )
    setPadding(dp(10), dp(6), dp(10), dp(6))
  }

  private fun chip(
    label: String,
    palette: ChipPalette,
  ): TextView = TextView(context).apply {
    text = label.uppercase()
    textSize = 11f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(palette.textColor)
    background = chipBackground(
      fillColor = palette.fillColor,
      borderColor = palette.borderColor,
      cornerDp = 12,
    )
    setPadding(dp(10), dp(6), dp(10), dp(6))
  }

  private fun titleText(
    value: String,
    textSizeSp: Float,
  ): TextView = context.ocCardTitleText(value, textSizeSp)

  private fun bodyText(value: String): TextView = context.ocBodyText(value)

  private fun helperText(value: String): TextView = context.ocMetaText(value)

  private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocCardBackground(OpenCraySurfaceTone.NEUTRAL, stroked = true)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), borderColor)
  }

  private fun railBackground(fillColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadii = floatArrayOf(
      dp(18).toFloat(),
      dp(18).toFloat(),
      0f,
      0f,
      0f,
      0f,
      dp(18).toFloat(),
      dp(18).toFloat(),
    )
    setColor(fillColor)
  }

  private fun chipBackground(
    fillColor: Int,
    borderColor: Int,
    cornerDp: Int,
  ): GradientDrawable = GradientDrawable().apply {
    val drawable = if (cornerDp >= OpenCrayUiTokens.radiusPill) {
      context.ocPillBackground(fillColor = fillColor, strokeColor = borderColor, strokeWidthDp = 1)
    } else {
      context.ocSurfaceBackground(
        fillColor = fillColor,
        radiusDp = cornerDp,
        strokeColor = borderColor,
        strokeWidthDp = 1,
      )
    }
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), borderColor)
  }

  private fun policyPalette(decision: ActionPolicyDecision): ChipPalette = when (decision) {
    ActionPolicyDecision.ALLOW -> ChipPalette(
      fillColor = Color.parseColor("#EAF7EF"),
      borderColor = Color.parseColor("#B7DFC7"),
      textColor = successColor,
    )

    ActionPolicyDecision.ASK -> ChipPalette(
      fillColor = Color.parseColor("#FFF4D6"),
      borderColor = Color.parseColor("#E7C36A"),
      textColor = warningColor,
    )

    ActionPolicyDecision.DENY -> ChipPalette(
      fillColor = Color.parseColor("#FCE8E6"),
      borderColor = Color.parseColor("#E8B4B0"),
      textColor = dangerColor,
    )
  }

  private fun resultPalette(status: ActionResultStatus): ChipPalette = when (status) {
    ActionResultStatus.SUCCESS -> ChipPalette(
      fillColor = Color.parseColor("#EAF7EF"),
      borderColor = Color.parseColor("#B7DFC7"),
      textColor = successColor,
    )

    ActionResultStatus.FAILED -> ChipPalette(
      fillColor = Color.parseColor("#FCE8E6"),
      borderColor = Color.parseColor("#E8B4B0"),
      textColor = dangerColor,
    )

    ActionResultStatus.TIMEOUT -> ChipPalette(
      fillColor = Color.parseColor("#FFF4D6"),
      borderColor = Color.parseColor("#E7C36A"),
      textColor = warningColor,
    )

    ActionResultStatus.CANCELLED -> ChipPalette(
      fillColor = Color.parseColor("#F1F3F5"),
      borderColor = Color.parseColor("#D0D5DD"),
      textColor = mutedColor,
    )
  }

  private fun approvalPalette(state: ActionApprovalState): ChipPalette = when (state) {
    ActionApprovalState.NOT_REQUIRED -> ChipPalette(
      fillColor = Color.parseColor("#F1F3F5"),
      borderColor = Color.parseColor("#D0D5DD"),
      textColor = mutedColor,
    )

    ActionApprovalState.REQUIRED -> ChipPalette(
      fillColor = Color.parseColor("#FFF4D6"),
      borderColor = Color.parseColor("#E7C36A"),
      textColor = warningColor,
    )

    ActionApprovalState.GRANTED -> ChipPalette(
      fillColor = Color.parseColor("#EAF2FF"),
      borderColor = Color.parseColor("#BED4FF"),
      textColor = accentColor,
    )
  }

  private fun blockParams(
    topDp: Int = 0,
    bottomDp: Int = 0,
  ): LinearLayout.LayoutParams = context.ocLinearBlockParams(topDp = topDp, bottomDp = bottomDp)

  private fun chipParams(startDp: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.WRAP_CONTENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
  ).apply {
    marginStart = dp(startDp)
  }

  private fun dp(value: Int): Int = context.ocDp(value)

  private data class ChipPalette(
    val fillColor: Int,
    val borderColor: Int,
    val textColor: Int,
  )
}

// Learning: Enum-backed models keep timeline rendering deterministic and easy to reuse from future screen state.
// Issue: This slice keeps local color tokens because the Views UI layer does not expose a shared style helper yet.
