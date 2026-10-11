package net.naicli.platform

import kotlin.test.*

class WindowsArgumentsTest {
    @Test fun `restores Unicode paths and quoted prompt text without changing argument boundaries`() {
        val arguments = listOf("prompt", "--prompt", "猫, poster \"TITLE\", <red|blue>",
            "--prompt-file", "C:\\nai 空格\\正向.txt", "--json")
        val narrow = arrayOf("prompt", "--prompt", "?, poster \"TITLE\", <red|blue>",
            "--prompt-file", "C:\\nai ??\\??.txt", "--json")
        val command = listOf("java.exe", "--enable-native-access=ALL-UNNAMED", "-classpath",
            "C:\\nai\\lib\\*", "net.naicli.MainKt") + arguments
        assertContentEquals(arguments.toTypedArray(), WindowsArguments.applicationArguments(command, narrow))
    }

    @Test fun `keeps JVM arguments when the command does not expose the installed main class`() {
        val arguments = arrayOf("history", "--json")
        assertSame(arguments, WindowsArguments.applicationArguments(listOf("java.exe", "@launch.txt"), arguments))
    }

    @Test fun `keeps JVM expansion of argument files and wildcard arguments`() {
        val prefix = listOf("java.exe", "net.naicli.MainKt")
        val arguments = arrayOf("prompt", "--prompt", "expanded")
        assertSame(arguments, WindowsArguments.applicationArguments(prefix + "@args.txt", arguments))
        assertSame(arguments, WindowsArguments.applicationArguments(prefix + listOf("prompt", "--prompt", "@one.txt"), arguments))
        assertSame(arguments, WindowsArguments.applicationArguments(prefix + listOf("history", "*"), arguments))
    }
}
