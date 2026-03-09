package com.opencray.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView

open class ComponentActivity : Activity()

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.WHITE)
      setPadding(dp(24), dp(32), dp(24), dp(24))
    }

    root.addView(labelView("Skills Management", 28f, Typeface.DEFAULT_BOLD, Color.BLACK))
    root.addView(labelView("Placeholder shell for Task 13", 14f, Typeface.DEFAULT, Color.DKGRAY).apply {
      setPadding(0, dp(8), 0, dp(24))
    })
    root.addView(labelView("Installed Skills", 18f, Typeface.DEFAULT_BOLD, Color.BLACK).apply {
      setPadding(0, 0, 0, dp(8))
    })
    root.addView(labelView("No installed skills yet", 16f, Typeface.DEFAULT, Color.DKGRAY).apply {
      setPadding(0, 0, 0, dp(20))
    })
    root.addView(labelView("Available Skills", 18f, Typeface.DEFAULT_BOLD, Color.BLACK).apply {
      setPadding(0, 0, 0, dp(8))
    })
    root.addView(labelView("Placeholder list pending", 16f, Typeface.DEFAULT, Color.DKGRAY))

    setContentView(root)
  }

  private fun labelView(text: String, sizeSp: Float, typeface: Typeface, color: Int): TextView {
    return TextView(this).apply {
      this.text = text
      this.typeface = typeface
      setTextColor(color)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

// Learning: A plain activity-backed view tree is enough for a stable launcher placeholder.
// Issue: This launcher stays intentionally static until the full skills flow is wired.
