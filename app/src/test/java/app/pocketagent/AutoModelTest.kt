package app.pocketagent

import app.pocketagent.ai.AutoModel
import app.pocketagent.ai.ModelCaps
import app.pocketagent.ai.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Auto: the right model for each message, chosen from the words alone. */
class AutoModelTest {
    private val claude = ModelCaps.BUILT_IN + ModelInfo("claude-opus-4-8", "Claude Opus 4.8") + ModelInfo("claude-sonnet-4-5-20250929", "Claude Sonnet 4.5")
    private fun pick(text: String, agent: Boolean = false, attachments: Int = 0, previous: String? = null) = AutoModel.pick(claude, text, agent, attachments, previous)

    @Test fun smallTalkAndQuickFactsGoToTheQuickModel() {
        assertEquals("claude-haiku-5-5", pick("hi"))
        assertEquals("claude-haiku-5-5", pick("Thanks a lot!"))
        assertEquals("claude-haiku-5-5", pick("What is the capital of Rajasthan?"))
        assertEquals("claude-haiku-5-5", pick("translate good morning to Hindi"))
    }

    @Test fun ordinaryQuestionsGoToTheEverydayModel() {
        assertEquals("claude-sonnet-5-5", pick("Give me a 7-day routine to start learning Python, with one small exercise for each day of the week"))
        assertEquals("claude-sonnet-5-5", pick("Help me write a polite message to my landlord asking to move the rent date to the fifth of the month"))
    }

    @Test fun demandingRequestsGoToTheStrongestModel() {
        assertEquals("claude-opus-5-5", pick("Debug this function and explain the root cause:\n```kotlin\nfun f(x: Int) = x / 0\n```"))
        assertEquals("claude-opus-5-5", pick("Analyse the trade-offs between these two database designs in detail and recommend one for a marketplace app."))
        assertEquals("claude-opus-5-5", pick("x ".repeat(900)))
        assertEquals("claude-opus-5-5", pick("Review this contract", attachments = 1))
    }

    @Test fun workNeverUsesTheQuickModel() {
        assertEquals("claude-sonnet-5-5", pick("list my files", agent = true))
        assertEquals("claude-opus-5-5", pick("Research today's top technology news and save a summary as notes.md", agent = true))
    }

    @Test fun aShortFollowUpStaysWithTheModelThatWasAnswering() {
        assertEquals("claude-opus-5-5", pick("and the second one?", previous = "claude-opus-5-5"))
        // A plain thank-you does not need it.
        assertEquals("claude-haiku-5-5", pick("thanks!", previous = "claude-opus-5-5"))
        // It can always step up.
        assertEquals("claude-opus-5-5", pick("Now analyse the security of that design step by step", previous = "claude-haiku-5-5"))
    }

    @Test fun theNewestOfAFamilyIsUsedAndTheSpecialOnesAreLeftAlone() {
        // Fable is only for someone who asks for it by name.
        assertEquals("claude-opus-5-5", pick("Prove that the square root of two is irrational, step by step, and review the proof"))
        val old = listOf(ModelInfo("claude-sonnet-4-5-20250929", "Sonnet 4.5"), ModelInfo("claude-sonnet-4-6", "Sonnet 4.6"))
        assertEquals("claude-sonnet-4-6", AutoModel.pick(old, "hello", false, 0))
    }

    @Test fun otherServicesAreSortedByTheirNames() {
        val openai = listOf(ModelInfo("gpt-5.2", "GPT-5.2"), ModelInfo("gpt-5.2-mini", "mini"), ModelInfo("gpt-5.2-pro", "pro"), ModelInfo("text-embedding-3-large", "embed"), ModelInfo("gpt-image-2", "image"))
        assertEquals("gpt-5.2-mini", AutoModel.pick(openai, "hey", false, 0))
        assertEquals("gpt-5.2", AutoModel.pick(openai, "Suggest three names for a bakery that sells sourdough and filter coffee near a college", false, 0))
        assertEquals("gpt-5.2-pro", AutoModel.pick(openai, "Design an algorithm to schedule exams with no clashes and analyse its complexity", false, 0))
        // A service with one kind of model still answers.
        assertEquals("llama-3.3-70b-versatile", AutoModel.pick(listOf(ModelInfo("llama-3.3-70b-versatile", "Llama")), "hi", false, 0))
        // OpenRouter's own router is used when it is there.
        assertEquals("openrouter/auto", AutoModel.pick(listOf(ModelInfo("x/y", "y"), ModelInfo("openrouter/auto", "Auto")), "hi", false, 0))
        assertNull(AutoModel.pick(emptyList(), "hi", false, 0))
    }
}
