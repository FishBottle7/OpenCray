package com.opencray.ui.design

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object OpenCrayUiTokens {
  val shellBackground: Int = Color.parseColor("#F5F5F7")
  val shellBackgroundMuted: Int = Color.parseColor("#EFF1F5")
  val surface: Int = Color.WHITE
  val surfaceMuted: Int = Color.parseColor("#F7F7FA")
  val surfaceInfo: Int = Color.parseColor("#EEF5FF")
  val surfaceSuccess: Int = Color.parseColor("#EEF8F2")
  val surfaceWarning: Int = Color.parseColor("#FFF6E8")
  val surfaceDanger: Int = Color.parseColor("#FFF1F0")
  val textPrimary: Int = Color.parseColor("#111111")
  val textSecondary: Int = Color.parseColor("#6E6E73")
  val textTertiary: Int = Color.parseColor("#8E8E93")
  val border: Int = Color.parseColor("#DCDCE1")
  val borderStrong: Int = Color.parseColor("#C7C7CC")
  val primary: Int = Color.parseColor("#007AFF")
  val success: Int = Color.parseColor("#34C759")
  val warning: Int = Color.parseColor("#FF9F0A")
  val danger: Int = Color.parseColor("#FF3B30")
  const val radiusInput: Int = 12
  const val radiusButton: Int = 14
  const val radiusCard: Int = 16
  const val radiusLargeCard: Int = 20
  const val radiusPill: Int = 999
  const val buttonHeight: Int = 52
  const val inputHeight: Int = 50
  const val compactInputHeight: Int = 44
}

enum class OpenCraySurfaceTone {
  NEUTRAL,
  SUBTLE,
  INFO,
  SUCCESS,
  WARNING,
  DANGER,
  ACCENT,
}

enum class OpenCrayButtonTone {
  PRIMARY,
  SECONDARY,
  DANGER,
  QUIET,
}

fun Context.ocDp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.ocLinearBlockParams(
  topDp: Int = 0,
  bottomDp: Int = 0,
): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
  ViewGroup.LayoutParams.MATCH_PARENT,
  ViewGroup.LayoutParams.WRAP_CONTENT,
).apply {
  topMargin = ocDp(topDp)
  bottomMargin = ocDp(bottomDp)
}

fun Context.ocSurfaceColor(tone: OpenCraySurfaceTone): Int = when (tone) {
  OpenCraySurfaceTone.NEUTRAL -> OpenCrayUiTokens.surface
  OpenCraySurfaceTone.SUBTLE -> OpenCrayUiTokens.surfaceMuted
  OpenCraySurfaceTone.INFO,
  OpenCraySurfaceTone.ACCENT -> OpenCrayUiTokens.surfaceInfo
  OpenCraySurfaceTone.SUCCESS -> OpenCrayUiTokens.surfaceSuccess
  OpenCraySurfaceTone.WARNING -> OpenCrayUiTokens.surfaceWarning
  OpenCraySurfaceTone.DANGER -> OpenCrayUiTokens.surfaceDanger
}

fun Context.ocAccentColor(tone: OpenCraySurfaceTone): Int = when (tone) {
  OpenCraySurfaceTone.SUCCESS -> OpenCrayUiTokens.success
  OpenCraySurfaceTone.WARNING -> OpenCrayUiTokens.warning
  OpenCraySurfaceTone.DANGER -> OpenCrayUiTokens.danger
  OpenCraySurfaceTone.INFO,
  OpenCraySurfaceTone.ACCENT -> OpenCrayUiTokens.primary
  OpenCraySurfaceTone.NEUTRAL,
  OpenCraySurfaceTone.SUBTLE -> OpenCrayUiTokens.border
}

fun Context.ocSurfaceBackground(
  fillColor: Int,
  radiusDp: Int = OpenCrayUiTokens.radiusCard,
  strokeColor: Int = Color.TRANSPARENT,
  strokeWidthDp: Int = 0,
): GradientDrawable = GradientDrawable().apply {
  shape = GradientDrawable.RECTANGLE
  cornerRadius = ocDp(radiusDp).toFloat()
  setColor(fillColor)
  setStroke(ocDp(strokeWidthDp), strokeColor)
}

fun Context.ocCardBackground(
  tone: OpenCraySurfaceTone = OpenCraySurfaceTone.NEUTRAL,
  radiusDp: Int = OpenCrayUiTokens.radiusCard,
  stroked: Boolean = false,
): GradientDrawable = ocSurfaceBackground(
  fillColor = ocSurfaceColor(tone),
  radiusDp = radiusDp,
  strokeColor = if (stroked) OpenCrayUiTokens.border else Color.TRANSPARENT,
  strokeWidthDp = if (stroked) 1 else 0,
)

fun Context.ocPillBackground(
  fillColor: Int,
  strokeColor: Int = Color.TRANSPARENT,
  strokeWidthDp: Int = 0,
): GradientDrawable = ocSurfaceBackground(
  fillColor = fillColor,
  radiusDp = OpenCrayUiTokens.radiusPill,
  strokeColor = strokeColor,
  strokeWidthDp = strokeWidthDp,
)

fun Context.ocButtonBackground(tone: OpenCrayButtonTone): GradientDrawable {
  val (fillColor, strokeColor) = when (tone) {
    OpenCrayButtonTone.PRIMARY -> OpenCrayUiTokens.primary to OpenCrayUiTokens.primary
    OpenCrayButtonTone.SECONDARY -> OpenCrayUiTokens.surfaceMuted to OpenCrayUiTokens.surfaceMuted
    OpenCrayButtonTone.DANGER -> OpenCrayUiTokens.danger to OpenCrayUiTokens.danger
    OpenCrayButtonTone.QUIET -> OpenCrayUiTokens.surface to OpenCrayUiTokens.border
  }
  return ocSurfaceBackground(
    fillColor = fillColor,
    radiusDp = OpenCrayUiTokens.radiusButton,
    strokeColor = strokeColor,
    strokeWidthDp = if (tone == OpenCrayButtonTone.QUIET) 1 else 0,
  )
}

fun Context.ocInputBackground(
  fillColor: Int = OpenCrayUiTokens.surface,
  strokeColor: Int = OpenCrayUiTokens.border,
): GradientDrawable = ocSurfaceBackground(
  fillColor = fillColor,
  radiusDp = OpenCrayUiTokens.radiusInput,
  strokeColor = strokeColor,
  strokeWidthDp = 1,
)

fun Context.ocTopBarBackground(): GradientDrawable = ocSurfaceBackground(
  fillColor = OpenCrayUiTokens.surface,
  radiusDp = 0,
  strokeColor = OpenCrayUiTokens.border,
  strokeWidthDp = 1,
)

fun Context.ocBottomNavBackground(): GradientDrawable = ocSurfaceBackground(
  fillColor = OpenCrayUiTokens.surface,
  radiusDp = 0,
  strokeColor = OpenCrayUiTokens.border,
  strokeWidthDp = 1,
)

fun Context.ocPageTitleText(value: String = ""): TextView = TextView(this).apply {
  text = value
  setTextColor(OpenCrayUiTokens.textPrimary)
  setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
  typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
  includeFontPadding = false
}

fun Context.ocSectionTitleText(
  value: String = "",
  textSizeSp: Float = 20f,
): TextView = TextView(this).apply {
  text = value
  setTextColor(OpenCrayUiTokens.textPrimary)
  setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
  typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
  includeFontPadding = false
}

fun Context.ocCardTitleText(
  value: String = "",
  textSizeSp: Float = 17f,
): TextView = TextView(this).apply {
  text = value
  setTextColor(OpenCrayUiTokens.textPrimary)
  setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
  typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
  includeFontPadding = false
}

fun Context.ocBodyText(value: String = ""): TextView = TextView(this).apply {
  text = value
  setTextColor(OpenCrayUiTokens.textPrimary)
  setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
  setLineSpacing(0f, 1.14f)
  includeFontPadding = false
}

fun Context.ocMetaText(value: String = ""): TextView = TextView(this).apply {
  text = value
  setTextColor(OpenCrayUiTokens.textSecondary)
  setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
  setLineSpacing(0f, 1.12f)
  includeFontPadding = false
}

fun Context.ocLabelText(
  value: String = "",
  color: Int = OpenCrayUiTokens.textSecondary,
): TextView = TextView(this).apply {
  text = value
  setTextColor(color)
  setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
  typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
  includeFontPadding = false
}

fun Context.ocSectionCard(
  tone: OpenCraySurfaceTone = OpenCraySurfaceTone.NEUTRAL,
  paddingDp: Int = 16,
): LinearLayout = LinearLayout(this).apply {
  orientation = LinearLayout.VERTICAL
  background = ocCardBackground(tone = tone)
  setPadding(ocDp(paddingDp), ocDp(paddingDp), ocDp(paddingDp), ocDp(paddingDp))
}

fun Context.ocButton(
  label: String,
  tone: OpenCrayButtonTone = OpenCrayButtonTone.PRIMARY,
): Button = Button(this).apply {
  text = label
  isAllCaps = false
  minHeight = ocDp(OpenCrayUiTokens.buttonHeight)
  minimumHeight = ocDp(OpenCrayUiTokens.buttonHeight)
  setTextColor(
    when (tone) {
      OpenCrayButtonTone.PRIMARY,
      OpenCrayButtonTone.DANGER -> Color.WHITE
      OpenCrayButtonTone.SECONDARY,
      OpenCrayButtonTone.QUIET -> OpenCrayUiTokens.textPrimary
    },
  )
  background = ocButtonBackground(tone)
  stateListAnimator = null
  setPadding(ocDp(20), ocDp(12), ocDp(20), ocDp(12))
}

fun Context.ocTextInput(
  hint: String,
  singleLine: Boolean = true,
  minLines: Int = if (singleLine) 1 else 3,
): EditText = EditText(this).apply {
  this.hint = hint
  setTextColor(OpenCrayUiTokens.textPrimary)
  setHintTextColor(OpenCrayUiTokens.textTertiary)
  background = ocInputBackground()
  minimumHeight = ocDp(OpenCrayUiTokens.inputHeight)
  setPadding(ocDp(14), ocDp(12), ocDp(14), ocDp(12))
  isSingleLine = singleLine
  if (singleLine) {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
  } else {
    this.minLines = minLines
    gravity = Gravity.TOP or Gravity.START
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
      InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
  }
}
