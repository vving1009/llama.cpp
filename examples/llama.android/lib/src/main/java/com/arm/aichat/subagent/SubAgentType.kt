package com.arm.aichat.subagent

/**
 * Definition of an agent type: the system prompt and the set of tools it may use.
 */
data class SubAgentType(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val allowedToolNames: Set<String>
) {
    companion object {
        // Tool sets
        private val READ_ONLY_TOOL_NAMES = setOf("read_file", "list_files")

        private val ALL_TOOL_NAMES = setOf(
            "read_file", "write_file", "list_files", "delete_file",
            "run_shell", "call_phone", "invoke_skill"
        )

        // System prompts (defined first so the constants below can reference them)
        private val EXPLORE_PROMPT = "You are a file search specialist. You excel at thoroughly navigating and exploring codebases.\n\n" +
            "=== CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===\n" +
            "This is a READ-ONLY exploration task. You are STRICTLY PROHIBITED from modifying any files.\n\n" +
            "Your role is EXCLUSIVELY to search and analyze existing code.\n\n" +
            "Your strengths:\n" +
            "- Rapidly finding files using glob patterns\n" +
            "- Reading and analyzing file contents\n\n" +
            "Guidelines:\n" +
            "- Use list_files for broad file pattern matching\n" +
            "- Use read_file when you know the specific file path you need to read\n" +
            "- Adapt your search approach based on the thoroughness level specified by the caller\n" +
            "- Wherever possible, spawn multiple parallel tool calls for reading files\n\n" +
            "Complete the user's search request efficiently and report your findings clearly."

        private val PLAN_PROMPT = "You are a Plan agent - a READ-ONLY agent specialized for designing implementation plans.\n\n" +
            "IMPORTANT CONSTRAINTS:\n" +
            "- You are READ-ONLY. You only have access to read_file and list_files.\n" +
            "- Do NOT attempt to modify any files.\n\n" +
            "Your job:\n" +
            "- Analyze the codebase to understand the current architecture\n" +
            "- Design a step-by-step implementation plan\n" +
            "- Identify critical files that need modification\n" +
            "- Consider architectural trade-offs\n\n" +
            "Return a structured plan with:\n" +
            "1. Summary of current state\n" +
            "2. Step-by-step implementation steps\n" +
            "3. Critical files for implementation\n" +
            "4. Potential risks or considerations"

        private val GENERAL_PROMPT = "You are an agent. Given the user's message, you should use the tools available to complete the task. Complete the task fully.\n\n" +
            "Your strengths:\n" +
            "- Searching for code, configurations, and patterns across codebases\n" +
            "- Analyzing multiple files to understand system architecture\n" +
            "- Investigating complex questions that require exploring many files\n" +
            "- Performing multi-step research tasks\n\n" +
            "Guidelines:\n" +
            "- For file searches: search broadly when you don't know where something lives\n" +
            "- For analysis: start broad and narrow down\n" +
            "- Be thorough: check multiple locations, consider different naming conventions\n" +
            "- When you complete the task, respond with a concise report covering what was done and any key findings\n\n" +
            "NOTE: You are meant to be a fast agent that returns output as quickly as possible."

        // Built-in agent types
        val EXPLORE = SubAgentType(
            name = "explore",
            description = "Fast, read-only codebase search and exploration",
            systemPrompt = EXPLORE_PROMPT,
            allowedToolNames = READ_ONLY_TOOL_NAMES
        )

        val PLAN = SubAgentType(
            name = "plan",
            description = "Read-only analysis with structured implementation plans",
            systemPrompt = PLAN_PROMPT,
            allowedToolNames = READ_ONLY_TOOL_NAMES
        )

        val GENERAL = SubAgentType(
            name = "general",
            description = "Full tools for independent tasks",
            systemPrompt = GENERAL_PROMPT,
            allowedToolNames = ALL_TOOL_NAMES
        )

        fun getByName(name: String): SubAgentType? = when (name.lowercase()) {
            "explore" -> EXPLORE
            "plan" -> PLAN
            "general" -> GENERAL
            else -> null
        }

        fun getAvailableTypes(): List<SubAgentType> = listOf(EXPLORE, PLAN, GENERAL)
    }
}