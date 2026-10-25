#!/data/data/com.termux/files/usr/bin/bash
cd ~/agentx || exit 1
OUT=/sdcard/Download/agentx-context-bundle.txt
B=app/src/main/java/com/androidharness/app
A=app/src/main/assets/skills

FILES="
$B/agent/AgentEngine.kt
$B/agent/HarnessEnvironmentPrompt.kt
$B/agent/ContextHygiene.kt
$B/agent/HistoryCompactor.kt
$B/agent/MemoryNotes.kt
$B/agent/MemoryTopics.kt
$B/agent/TodoPrompt.kt
$B/agent/TodoStore.kt
$B/agent/RemotePromptIdentity.kt
$B/agent/RunManager.kt
$B/agent/SubagentConfiguration.kt
$B/agent/CustomReasoning.kt
$B/agent/LiveCode.kt
$B/caveman/CavemanPolicy.kt
$B/data/MemoryRepository.kt
$B/data/ConversationConfig.kt
$B/data/ConversationStartup.kt
$B/workspace/ProjectMemory.kt
$B/repomap/RepoMapGenerator.kt
$B/repomap/RepoMapCache.kt
$B/skills/Skill.kt
$B/skills/SkillStore.kt
$B/skills/SkillParser.kt
$B/skills/SkillAssets.kt
$B/tools/Tool.kt
$B/tools/SkillTools.kt
$B/tools/MemoryTools.kt
$B/tools/UniversalMemoryTools.kt
$B/tools/CodeGraphTools.kt
$B/tools/ChatRecallTools.kt
$B/tools/mcp/McpManager.kt
$B/tools/mcp/McpToolAdapter.kt
$B/llm/Llm.kt
$B/llm/PromptCachePolicy.kt
$B/llm/OpenAiCompatProvider.kt
$B/llm/AnthropicProvider.kt
$B/ui/chat/ChatViewModel.kt
$B/ui/chat/ContextPanel.kt
$B/ui/chat/CacheUsage.kt
repowiki/04-agent-execution-engine.md
repowiki/06-context-pruning-and-sanitization.md
repowiki/11-agent-long-term-memory-topic-notes.md
repowiki/19-tool-definition-dispatch-system.md
repowiki/26-skill-extension-tools.md
"

: > "$OUT"

echo "##### INDEX: system prompt / tool schema references #####" >> "$OUT"
grep -rnE "systemPrompt|buildSystem|SYSTEM_PROMPT|toolDefinitions|toolSpecs|allTools|registry" "$B" --include=*.kt | head -80 >> "$OUT"
echo >> "$OUT"

for f in $FILES; do
  if [ -f "$f" ]; then
    {
      echo "=================================================="
      echo "FILE: $f ($(wc -l < "$f") lines)"
      echo "=================================================="
      cat "$f"
      echo
    } >> "$OUT"
  else
    echo "MISSING: $f"
  fi
done

echo "##### SKILL.md HEADS (first 12 lines each) #####" >> "$OUT"
for f in $A/*/SKILL.md; do
  echo "--- $f" >> "$OUT"
  head -n 12 "$f" >> "$OUT"
done

ls -la "$OUT"
