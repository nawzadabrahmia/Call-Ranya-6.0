package edu.uor.lancall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Remote-support control on THIS phone.
 * - Must be switched on by the user in Android Settings → Accessibility (Android shows its own warning).
 * - Does nothing unless a support session is active AND the user tapped "Allow view & control".
 * - Never reads screen content or sends anything anywhere; it only performs the helper's taps/typing.
 */
class RemoteAccessService : AccessibilityService() {

    companion object {
        @Volatile var instance: RemoteAccessService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return s.split(':').any {
                ComponentName.unflattenFromString(it)?.let { c ->
                    c.packageName == ctx.packageName && c.className == RemoteAccessService::class.java.name
                } == true
            }
        }
    }

    private var path: Path? = null
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun screen(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun px(o: JSONObject, kx: String = "x", ky: String = "y"): Pair<Float, Float> {
        val (w, h) = screen()
        val x = o.optDouble(kx, 0.5).coerceIn(0.0, 1.0) * (w - 1)
        val y = o.optDouble(ky, 0.5).coerceIn(0.0, 1.0) * (h - 1)
        return x.toFloat() to y.toFloat()
    }

    private fun gesture(p: Path, ms: Long) {
        val d = ms.coerceIn(1L, 5000L)
        runCatching { dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, d)).build(), null, null) }
    }

    private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    /** Events from the helper. Only called by CallService while control is allowed. */
    fun handle(o: JSONObject) {
        when (o.optString("t")) {
            "tap" -> { val (x, y) = px(o); gesture(point(x, y), 60) }
            "long" -> { val (x, y) = px(o); gesture(point(x, y), 800) }
            "swipe" -> {
                val (x1, y1) = px(o, "x1", "y1"); val (x2, y2) = px(o, "x2", "y2")
                gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, o.optLong("ms", 300))
            }
            // mouse from a PC helper: down / move / up become one gesture
            "down" -> { val (x, y) = px(o); path = point(x, y); downAt = System.currentTimeMillis(); downX = x; downY = y; moved = false }
            "move" -> path?.let { p -> val (x, y) = px(o); if (kotlin.math.hypot(x - downX, y - downY) > 12) moved = true; p.lineTo(x, y) }
            "up" -> {
                val p = path ?: return
                path = null
                val dt = System.currentTimeMillis() - downAt
                when {
                    o.optInt("b") == 2 -> gesture(point(downX, downY), 800)            // right-click = long press
                    !moved -> gesture(point(downX, downY), if (dt > 600) 800 else 60)
                    else -> { val (x, y) = px(o); p.lineTo(x, y); gesture(p, dt.coerceIn(80, 3000)) }
                }
            }
            "wheel" -> {
                val (w, h) = screen(); val dy = o.optDouble("dy")
                val x = w / 2f; val y1 = h * 0.6f; val y2 = if (dy > 0) h * 0.35f else h * 0.85f
                gesture(Path().apply { moveTo(x, y1); lineTo(x, y2) }, 250)
            }
            "key" -> when (o.optString("k")) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "backspace" -> backspace()
            }
            "text" -> typeText(o.optString("s"))
            "kd" -> {
                val key = o.optString("key")
                when {
                    key == "Backspace" -> backspace()
                    key == "Escape" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    key == "Enter" -> typeText("\n")
                    key.length == 1 -> typeText(key)
                }
            }
        }
    }

    private fun focused(): AccessibilityNodeInfo? = runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()

    private fun currentText(n: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
        return n.text?.toString() ?: ""
    }

    private fun setText(n: AccessibilityNodeInfo, t: String) {
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    private fun typeText(s: String) { val n = focused() ?: return; setText(n, currentText(n) + s.take(500)) }
    private fun backspace() { val n = focused() ?: return; setText(n, currentText(n).dropLast(1)) }
}
