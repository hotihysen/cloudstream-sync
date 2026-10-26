package android.util

/** Ersatz für android.util.Log (von Erweiterungen und dem gemeinsamen Katalog benutzt). */
object Log {
    @JvmStatic fun v(tag: String?, msg: String?): Int = out("V", tag, msg, null)
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = out("V", tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String?): Int = out("D", tag, msg, null)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = out("D", tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String?): Int = out("I", tag, msg, null)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = out("I", tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String?): Int = out("W", tag, msg, null)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = out("W", tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = out("W", tag, null, tr)
    @JvmStatic fun e(tag: String?, msg: String?): Int = out("E", tag, msg, null)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = out("E", tag, msg, tr)
    @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString() ?: ""

    @Volatile var verbose = System.getenv("FLIMMER_VERBOSE") != null

    private fun out(level: String, tag: String?, msg: String?, tr: Throwable?): Int {
        if (!verbose && (level == "V" || level == "D")) return 0
        System.err.println("$level/$tag: ${msg ?: ""}${tr?.let { " · " + it.javaClass.simpleName + ": " + it.message } ?: ""}")
        return 0
    }
}
