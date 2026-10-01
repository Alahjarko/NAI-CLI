package net.naicli.commands

import kotlin.test.*
import net.naicli.*

class ApproveInputTest {
    private fun collect(argv: List<String> = emptyList(), answers: List<String>): Pair<ApproveInput, List<String>> {
        val remaining = answers.toMutableList()
        val prompts = mutableListOf<String>()
        val input = ApproveInput.collect(Args.parse(argv), { prompt ->
            prompts += prompt
            if (remaining.isEmpty()) null else remaining.removeAt(0)
        }, {})
        assertTrue(remaining.isEmpty(), "All expected answers must be consumed")
        return input to prompts
    }

    @Test fun `bare invocation offers defaults and optional expiry`() {
        val (input, prompts) = collect(answers = listOf("", "", " 测试用途 ", ""))
        assertEquals(ApproveInput(0, 1, "测试用途", null), input)
        assertNull(input.ttlMillis)
        assertEquals(4, prompts.size)
    }

    @Test fun `wizard accepts a finite expiry`() {
        val (input, _) = collect(answers = listOf("20", "3", "测试用途", "30"))
        assertEquals(ApproveInput(20, 3, "测试用途", 30), input)
        assertEquals(1_800_000L, input.ttlMillis)
    }

    @Test fun `zero expiry also means no expiry`() {
        val (input, _) = collect(answers = listOf("0", "2", "测试用途", "0"))
        assertNull(input.ttlMillis)
    }

    @Test fun `invalid interactive fields are retried without advancing`() {
        val (input, prompts) = collect(answers = listOf(
            "-1", "not a number", "9223372036854775808", "10",
            "0", "2147483648", "2", "  ", "用途", "-3", "1.5", "15",
        ))
        assertEquals(ApproveInput(10, 2, "用途", 15), input)
        assertEquals(12, prompts.size)
    }

    @Test fun `complete legacy flags require no extra questions`() {
        val (input, prompts) = collect(listOf("--anlas", "10", "--purpose", "用途"), emptyList())
        assertEquals(ApproveInput(10, 1, "用途", null), input)
        assertTrue(prompts.isEmpty())
        val (finite, _) = collect(listOf("--anlas", "0", "--purpose", "用途", "--calls", "4", "--minutes", "30"), emptyList())
        assertEquals(1_800_000L, finite.ttlMillis)
        assertEquals(4, finite.calls)
    }

    @Test fun `partial flags prefill the wizard without asking supplied values again`() {
        val (input, prompts) = collect(listOf("--anlas", "7", "--calls", "3", "--minutes", "0"), listOf("用途"))
        assertEquals(ApproveInput(7, 3, "用途", 0), input)
        assertEquals(1, prompts.size)
        assertTrue(prompts.single().contains("用途"))
    }

    @Test fun `EOF at any wizard step cancels without returning an authorization input`() {
        for (count in 0..3) {
            val answers = listOf("0", "1", "用途").take(count).toMutableList()
            val failure = assertFailsWith<CliFailure> {
                ApproveInput.collect(Args.parse(emptyList()), {
                    if (answers.isEmpty()) null else answers.removeAt(0)
                }, {})
            }
            assertEquals(ExitCode.SPEND_REFUSED, failure.exitCode)
        }
    }

    @Test fun `invalid explicit options fail before prompting and never fall back to defaults`() {
        for (args in listOf(
            listOf("--anlas", "-1"), listOf("--anlas", "no"),
            listOf("--calls", "0"), listOf("--calls", "2147483648"),
            listOf("--purpose", "  "), listOf("--minutes", "-1"),
        )) {
            val failure = assertFailsWith<CliFailure> {
                ApproveInput.collect(Args.parse(args), { error("must not prompt") }, {})
            }
            assertEquals(ExitCode.USAGE, failure.exitCode)
        }
    }
}
