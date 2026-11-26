package com.signalless.app.util

/**
 * Logging facade for the portable core.
 *
 * The core cannot depend on `android.util.Log`, so it emits through this
 * object. Android installs a sink at process start to forward into logcat;
 * the desktop relay leaves it unset and gets stdout instead.
 */
object SLog {
    @Volatile
    var sink: ((level: Char, tag: String, message: String, throwable: Throwable?) -> Unit)? = null

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        emit('d', tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) =
        emit('i', tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        emit('w', tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        emit('e', tag, message, throwable)

    private fun emit(level: Char, tag: String, message: String, throwable: Throwable?) {
        val target = sink
        if (target != null) {
            target(level, tag, message, throwable)
        } else {
            println("$level/$tag: $message")
            throwable?.printStackTrace()
        }
    }
}
