package net.naicli.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/** The JDK Windows launcher can replace characters outside the native ANSI code page with '?'. */
internal object WindowsArguments {
    private interface Kernel32 : StdCallLibrary {
        fun GetCommandLineW(): Pointer
        fun LocalFree(memory: Pointer): Pointer?
    }

    private interface Shell32 : StdCallLibrary {
        fun CommandLineToArgvW(commandLine: WString, count: IntByReference): Pointer?
    }

    fun restore(fallback: Array<String>): Array<String> {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return fallback
        val kernel = Native.load("kernel32", Kernel32::class.java)
        val shell = Native.load("shell32", Shell32::class.java)
        val count = IntByReference()
        val arguments = shell.CommandLineToArgvW(WString(kernel.GetCommandLineW().getWideString(0)), count)
            ?: throw IllegalStateException("Cannot read the Windows Unicode command line")
        try {
            val wide = arguments.getPointerArray(0, count.value).map { it.getWideString(0) }
            return applicationArguments(wide, fallback)
        } finally {
            kernel.LocalFree(arguments)
        }
    }

    internal fun applicationArguments(wide: List<String>, fallback: Array<String>): Array<String> {
        // Both Gradle launchers invoke this class directly. Preserve JVM expansion for other
        // launch forms and argument files instead of guessing their application boundaries.
        val main = wide.indexOf("net.naicli.MainKt")
        if (main < 0) return fallback
        val restored = wide.drop(main + 1)
        if (restored.size != fallback.size || restored.any { it.startsWith("@") }) return fallback
        return restored.toTypedArray()
    }
}
