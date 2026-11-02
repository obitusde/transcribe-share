package io.github.obitusde.aufnahme

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Farben und Bausteine der Oberflaeche, hell und dunkel. */
class Ui(private val c: Context) {

    val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private fun pick(light: String, darkColor: String): Int = Color.parseColor(if (dark) darkColor else light)

    val bg = pick("#F3F4F7", "#111318")
    val card = pick("#FFFFFF", "#1C1F26")
    val text = pick("#161A21", "#ECEEF2")
    val sub = pick("#5C6370", "#A2A9B6")
    val line = pick("#E2E5EA", "#2C313B")
    val field = pick("#F3F4F7", "#262A33")

    val accent = pick("#1E3A8A", "#9DB4F5")
    val onAccent = pick("#FFFFFF", "#0D1A3D")
    val accentSoft = pick("#E5EBFA", "#1E2945")

    val red = pick("#D32F2F", "#F2726F")
    val onRed = pick("#FFFFFF", "#2B0707")
    val redSoft = pick("#FDECEC", "#3A1C1D")

    val orange = pick("#B45309", "#F6B260")
    val orangeSoft = pick("#FFF4E5", "#3A2B17")

    val green = pick("#2E7D32", "#7FD18A")
    val greenSoft = pick("#E9F6EA", "#1A3020")

    val grey = pick("#5C6370", "#A2A9B6")
    val greySoft = pick("#EEF0F3", "#252932")

    fun dp(v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) d.setStroke(dp(1), stroke)
        return d
    }

    fun text(s: CharSequence, sizeSp: Float = 15f, color: Int = text, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        t.setLineSpacing(0f, 1.15f)
        return t
    }

    enum class Kind { PRIMARY, RECORD, TONAL, OUTLINE, DANGER, PLAIN }

    fun button(label: String, kind: Kind, big: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = label
        b.isAllCaps = false
        b.textSize = if (big) 20f else 15f
        b.stateListAnimator = null
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = dp(if (big) 64 else 46)
        b.minimumHeight = dp(if (big) 64 else 46)
        b.setPadding(dp(16), 0, dp(16), 0)
        b.gravity = Gravity.CENTER
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        val radius = if (big) 32 else 23
        val (fill, fg, stroke) = when (kind) {
            Kind.PRIMARY -> Triple(accent, onAccent, null)
            Kind.RECORD -> Triple(red, onRed, null)
            Kind.TONAL -> Triple(accentSoft, accent, null)
            Kind.OUTLINE -> Triple(Color.TRANSPARENT, text, line)
            Kind.DANGER -> Triple(Color.TRANSPARENT, red, line)
            Kind.PLAIN -> Triple(Color.TRANSPARENT, sub, null)
        }
        val shape = rounded(fill, radius, stroke)
        val mask = rounded(Color.BLACK, radius)
        val ripple = if (dark) Color.argb(60, 255, 255, 255) else Color.argb(40, 0, 0, 0)
        b.background = RippleDrawable(ColorStateList.valueOf(ripple), shape, mask)
        b.setTextColor(fg)
        b.setOnClickListener { onClick() }
        return b
    }

    /** Karte, optional mit farbigem Streifen links. */
    fun card(stripe: Int? = null, background: Int = card): LinearLayout {
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        if (stripe == null) {
            box.background = rounded(background, 18)
            box.setPadding(dp(16), dp(14), dp(16), dp(14))
        } else {
            val layers = LayerDrawable(arrayOf<Drawable>(rounded(stripe, 18), rounded(background, 18)))
            layers.setLayerInset(1, dp(5), 0, 0, 0)
            box.background = layers
            box.setPadding(dp(19), dp(14), dp(16), dp(14))
        }
        return box
    }

    /** Kleines farbiges Etikett, z. B. "Noch nicht entschieden". */
    fun badge(label: String, fg: Int, bgColor: Int): TextView {
        val t = text(label, 13f, fg, bold = true)
        t.background = rounded(bgColor, 12)
        t.setPadding(dp(10), dp(3), dp(10), dp(4))
        return t
    }

    fun full(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    fun wrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    fun weighted(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    fun margins(p: LinearLayout.LayoutParams, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
        p.setMargins(dp(l), dp(t), dp(r), dp(b))
        return p
    }

    /** Zwei oder mehr Knoepfe nebeneinander, gleich breit. */
    fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(c)
        r.orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            r.addView(v, margins(weighted(), l = if (i == 0) 0 else 5, r = if (i == views.lastIndex) 0 else 5))
        }
        return r
    }
}

/** Legt Kinder nebeneinander und bricht in die naechste Zeile um, wenn der Platz nicht reicht. */
class FlowLayout(context: Context, private val gapPx: Int) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        val childSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val w = child.measuredWidth
            if (x > 0 && x + w > maxWidth) {
                x = 0
                y += rowHeight + gapPx
                rowHeight = 0
            }
            x += w + gapPx
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            y + rowHeight + paddingTop + paddingBottom
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val w = child.measuredWidth
            val h = child.measuredHeight
            if (x > 0 && x + w > maxWidth) {
                x = 0
                y += rowHeight + gapPx
                rowHeight = 0
            }
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + w, paddingTop + y + h)
            x += w + gapPx
            rowHeight = maxOf(rowHeight, h)
        }
    }
}
