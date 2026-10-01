package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.viewmodel.finalToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ToolSummaryRegressionTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun everyFailureKindShowsItsConcreteReasonBeforeGenericActionText() {
        for (name in listOf("read_memory_file", "read_skill_file", "file_read", "execute_shell_command", "web_search", "mcp_test", "unknown")) {
            val segment = MessageSegment(type = "tool", toolName = name,
                toolResult = "Error: command timeout", toolState = ToolExecutionStates.FAILED)
            assertEquals("Command timeout", resources.toolSummary(segment))
        }
        val generic = MessageSegment(type = "tool", toolName = "mcp_test", toolResult = "", toolState = ToolExecutionStates.FAILED)
        assertEquals("Tool call failed", resources.toolSummary(generic))
        assertEquals("Tool call failed", resources.toolSummary(generic.copy(toolResult = "Error:")))
    }

    @Test
    fun successfulReadTextNeverDeclaresFailureByItsSpelling() {
        for (name in listOf("read_memory_file", "read_skill_file", "mcp_test_read_abcdef")) {
            for (text in listOf("Error handling guidelines", "Errors are examples", "Error: an example", """{"error":"example"}""")) {
                val state = finalToolState(ToolExecutionResult(text), name)
                val presentation = ToolPresentationResolver.resolve(
                    MessageSegment(type = "tool", toolName = name, toolResult = text, toolState = state),
                )
                assertEquals(ToolExecutionStates.SUCCEEDED, state)
                assertEquals(ToolPresentationState.COMPLETED, presentation.state)
                assertNull(presentation.errorMessage)
                assertEquals(text, presentation.rawResult)
            }
        }
    }

    @Test
    fun declaredErrorRetainsRawResultAndProvidesTheReason() {
        val text = "Error executing tool 'read_memory_file': command timeout"
        val state = finalToolState(ToolExecutionResult(text, isError = true), "read_memory_file")
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(type = "tool", toolName = "read_memory_file", toolResult = text, toolState = state),
        )
        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals(text, presentation.rawResult)
        assertEquals("Command timeout", toolFailureReasonSummary(presentation.errorMessage))
    }

    @Test
    fun reasonSummaryPreservesIdentifiersAndOnlyCapitalizesNaturalLanguage() {
        assertEquals("Command timeout", toolFailureReasonSummary("Error: command timeout."))
        assertEquals("Permission denied: /tmp/MixedCase", toolFailureReasonSummary("permission denied: /tmp/MixedCase"))
        assertEquals("myFile.md not found", toolFailureReasonSummary("Error: myFile.md not found"))
        assertEquals("/tmp/MixedCase missing", toolFailureReasonSummary("/tmp/MixedCase missing"))
        assertEquals("old_string is required", toolFailureReasonSummary("old_string is required"))
        assertNull(toolFailureReasonSummary("Error:"))
        assertNull(toolFailureReasonSummary("error"))
        assertNull(toolFailureReasonSummary(null))
    }

    @Test
    fun structuredReasonAndNoResultsKeepTheirDeclaredSemantics() {
        val error = ToolExecutionResult("protocol", structuredContent = """{"error":"command_timeout","message":"command timeout"}""")
        assertEquals(ToolExecutionStates.FAILED, finalToolState(error, "mcp_test_tool_abcdef"))
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(type = "tool", toolName = "mcp_test_tool_abcdef", toolResult = error.text,
                toolStructuredResult = error.structuredContent, toolState = ToolExecutionStates.FAILED),
        )
        assertEquals("Command timeout", toolFailureReasonSummary(presentation.errorMessage))
        assertEquals(ToolExecutionStates.EMPTY, finalToolState(
            ToolExecutionResult("""{"error":"no_results"}""", isError = true), "web_search",
        ))
        assertEquals(ToolExecutionStates.SUCCEEDED, finalToolState(
            ToolExecutionResult("""{"exit_code":7,"state":"failed"}"""), "execute_shell_command",
        ))
    }
}
