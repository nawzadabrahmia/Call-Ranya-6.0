package androidx.core.app
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
class NotificationCompat {
    companion object {
        const val PRIORITY_MAX = 2; const val PRIORITY_HIGH = 1; const val PRIORITY_DEFAULT = 0; const val PRIORITY_LOW = -1
        const val CATEGORY_ALARM = "alarm"; const val CATEGORY_MESSAGE = "msg"; const val CATEGORY_REMINDER = "reminder"; const val CATEGORY_STATUS = "status"; const val CATEGORY_CALL = "call"
        const val VISIBILITY_PUBLIC = 1
    }
    open class Style
    class BigTextStyle : Style() { fun bigText(t: CharSequence?): BigTextStyle = this }
    class Builder(c: Context, ch: String) {
        fun setSmallIcon(i: Int) = this; fun setContentTitle(t: CharSequence?) = this; fun setContentText(t: CharSequence?) = this
        fun setStyle(s: Style?) = this; fun setPriority(p: Int) = this; fun setCategory(c: String?) = this
        fun setAutoCancel(b: Boolean) = this; fun setContentIntent(p: PendingIntent?) = this
        fun addAction(i: Int, t: CharSequence?, p: PendingIntent?) = this
        fun build(): Notification = Notification()
    }
}
