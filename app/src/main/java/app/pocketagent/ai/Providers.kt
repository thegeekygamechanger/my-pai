package app.pocketagent.ai

import kotlinx.serialization.Serializable

/**
 * A service the app can talk to with the user's own key. Claude uses Anthropic's own API; nearly everyone
 * else offers the same "chat completions" shape that OpenAI introduced, so one client covers them all.
 */
@Serializable
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** "anthropic" or "openai" (the OpenAI-compatible shape). */
    val kind: String = "openai",
    /** Where a person gets a key. */
    val keyUrl: String = "",
    val keyHint: String = "",
    val blurb: String = "",
    val custom: Boolean = false,
)

object Providers {
    const val ANTHROPIC = "anthropic"

    val PRESETS = listOf(
        Provider(ANTHROPIC, "Claude", "https://api.anthropic.com", "anthropic", "https://platform.claude.com/settings/keys", "sk-ant-…", "Anthropic"),
        Provider("openai", "OpenAI", "https://api.openai.com/v1", "openai", "https://platform.openai.com/api-keys", "sk-…", "GPT models"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai", "https://openrouter.ai/keys", "sk-or-…", "Hundreds of models, some free"),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1", "openai", "https://console.groq.com/keys", "gsk_…", "Very fast open models"),
        Provider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "openai", "https://aistudio.google.com/apikey", "AIza…", "Google"),
        Provider("mistral", "Mistral", "https://api.mistral.ai/v1", "openai", "https://console.mistral.ai/api-keys", "", "Mistral and Codestral"),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "openai", "https://platform.deepseek.com/api_keys", "sk-…", "DeepSeek chat and reasoner"),
        Provider("together", "Together", "https://api.together.xyz/v1", "openai", "https://api.together.ai/settings/api-keys", "", "Open models"),
        Provider("fireworks", "Fireworks", "https://api.fireworks.ai/inference/v1", "openai", "https://fireworks.ai/account/api-keys", "fw_…", "Open models"),
        Provider("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "openai", "https://cloud.cerebras.ai", "csk-…", "Very fast open models"),
        Provider("xai", "xAI", "https://api.x.ai/v1", "openai", "https://console.x.ai", "xai-…", "Grok"),
        Provider("nvidia", "NVIDIA", "https://integrate.api.nvidia.com/v1", "openai", "https://build.nvidia.com", "nvapi-…", "Open models"),
        Provider("huggingface", "Hugging Face", "https://router.huggingface.co/v1", "openai", "https://huggingface.co/settings/tokens", "hf_…", "Open models"),
    )

    fun vaultKey(providerId: String) = "key.$providerId"

    /** A provider the user described themselves: any service that speaks the OpenAI-compatible shape. */
    fun custom(name: String, baseUrl: String) = Provider(
        id = "custom-" + name.lowercase().filter { it.isLetterOrDigit() }.take(16).ifEmpty { "x" } + "-" + (baseUrl.hashCode().toUInt() % 10000u),
        name = name.trim().ifEmpty { "Custom" }, baseUrl = baseUrl.trim().trimEnd('/'), kind = "openai", custom = true, blurb = "Your own endpoint",
    )

    /** Whether the reasoning-effort control does anything for this model. */
    fun efforts(provider: Provider, model: String): List<String> = when {
        // On Auto the model is not known yet; the setting is offered where any model of the service may use it, and fitted to the model when it is chosen.
        model == AutoModel.ID -> if (provider.kind == "anthropic" || provider.id == "openai" || provider.id == "openrouter") listOf("low", "medium", "high") else emptyList()
        provider.kind == "anthropic" -> ModelCaps.efforts(model)
        provider.id == "openai" && (model.startsWith("o") && model.getOrNull(1)?.isDigit() == true || model.startsWith("gpt-5")) -> listOf("low", "medium", "high")
        provider.id == "openrouter" -> listOf("low", "medium", "high")
        else -> emptyList()
    }

    /** Web search and the code sandbox run on Anthropic's side, so only Claude models have them. */
    fun serverTools(provider: Provider) = provider.kind == "anthropic"
}

/** A reply while it is still arriving, whichever service it comes from. */
interface LiveReply {
    val id: String
    val model: String
    fun live(): List<LiveBlock>
    fun partialText(): String
}
