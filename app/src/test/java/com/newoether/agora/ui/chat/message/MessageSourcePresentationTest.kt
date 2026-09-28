package com.newoether.agora.ui.chat.message

import androidx.compose.ui.graphics.Color
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MessageSourcePresentationTest {
    private val source = MessageSource.askUser(
        listOf(
            MessageSource.AskUserItem("Which port?", "8080"),
            MessageSource.AskUserItem("Restart now?", null),
        ),
    )

    @Test
    fun `ask_user display text uses the localized unanswered label`() {
        assertEquals(
            "Which port?\n8080\n\nRestart now?\n未回答",
            askUserDisplayText(source, "未回答"),
        )
    }

    @Test
    fun `styled text has the same characters and dims questions and skipped answers`() {
        val text = askUserDisplayText(source, "未回答")
        val styled = askUserDisplayAnnotated(source, "未回答", Color.White)
        assertEquals(text, styled.text)
        val dimmed = styled.spanStyles.map { styled.text.substring(it.start, it.end) }
        assertEquals(listOf("Which port?", "Restart now?", "未回答"), dimmed)
        styled.spanStyles.forEach { assertEquals(ASK_USER_DIM_ALPHA, it.item.color.alpha, 0.01f) }
    }

    @Test
    fun `only ask_user messages change their visible text`() {
        val askUser = ChatMessage(text = source.askUserReadableText(), participant = Participant.USER, source = source)
        assertEquals(askUserDisplayText(source, "-"), askUser.withAskUserDisplayText("-").text)
        val task = ChatMessage(text = "Run", participant = Participant.USER, source = MessageSource.TASK)
        assertSame(task, task.withAskUserDisplayText("-"))
        val typed = ChatMessage(text = "hi", participant = Participant.USER)
        assertSame(typed, typed.withAskUserDisplayText("-"))
    }
}
