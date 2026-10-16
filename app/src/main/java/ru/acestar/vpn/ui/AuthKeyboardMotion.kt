package ru.acestar.vpn.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ScrollView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.transition.ChangeBounds
import androidx.transition.ChangeImageTransform
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet

/** Compact the auth header when the keyboard opens; keep the form scrollable. */
object AuthKeyboardMotion {
    fun attach(scroll: ScrollView, logo: View, title: View, card: View, button: View) {
        val content = logo.parent as ConstraintLayout
        val expanded = ConstraintSet().apply { clone(content) }
        val compact = ConstraintSet().apply {
            clone(content)
            val density = scroll.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            constrainWidth(logo.id, dp(48))
            constrainHeight(logo.id, dp(48))
            setMargin(logo.id, ConstraintSet.TOP, dp(4))
            setMargin(title.id, ConstraintSet.TOP, dp(4))
            setMargin(card.id, ConstraintSet.TOP, dp(10))
        }
        var keyboardShown = false
        fun applyKeyboard(shown: Boolean) {
            if (shown != keyboardShown) {
                keyboardShown = shown
                TransitionManager.beginDelayedTransition(content, TransitionSet().apply {
                    addTransition(ChangeBounds())
                    addTransition(ChangeImageTransform())
                    duration = 260
                    interpolator = AccelerateDecelerateInterpolator()
                })
                (if (shown) compact else expanded).applyTo(content)
                scroll.postDelayed({
                    if (keyboardShown) {
                        val target = Rect()
                        button.getDrawingRect(target)
                        scroll.offsetDescendantRectToMyCoords(button, target)
                        val focused = scroll.findFocus()
                        if (focused != null) {
                            val field = Rect()
                            focused.getDrawingRect(field)
                            scroll.offsetDescendantRectToMyCoords(focused, field)
                            // Prefer showing both the focused field and the action button.
                            target.top = field.top
                        }
                        if (target.height() <= scroll.height - scroll.paddingTop - scroll.paddingBottom) {
                            scroll.smoothScrollTo(0, maxOf(0, target.bottom - scroll.height + scroll.paddingBottom + 16))
                        }
                    } else scroll.smoothScrollTo(0, 0)
                }, 280)
            }
        }
        fun keyboardVisible(): Boolean {
            val visible = Rect()
            scroll.getWindowVisibleDisplayFrame(visible)
            val obscured = scroll.rootView.height - visible.bottom
            return ViewCompat.getRootWindowInsets(scroll)?.isVisible(WindowInsetsCompat.Type.ime()) == true ||
                obscured > 120 * scroll.resources.displayMetrics.density
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            applyKeyboard(insets.isVisible(WindowInsetsCompat.Type.ime()) || keyboardVisible())
            insets
        }
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { applyKeyboard(keyboardVisible()) }
        scroll.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                scroll.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                scroll.removeOnAttachStateChangeListener(this)
            }
        })
        ViewCompat.requestApplyInsets(scroll)
    }
}
