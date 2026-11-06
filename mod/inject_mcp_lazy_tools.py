#!/usr/bin/env python3
"""Inject MCP lazy tools into ChatToolFactory.kt (upstream moved tools out of ChatService)."""
from pathlib import Path
import sys

CANDIDATES = [
    Path("app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt"),
    Path("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt"),
]

path = next((p for p in CANDIDATES if p.exists()), None)
if path is None:
    print("[FAIL] ChatToolFactory.kt / ChatService.kt not found")
    sys.exit(1)

text = path.read_text(encoding="utf-8")

if "mcp_list_tools" in text and "mcp_call_tool" in text:
    print(f"[SKIP] MCP lazy tools already present in {path}")
    sys.exit(0)

if path.name != "ChatToolFactory.kt":
    print(f"[FAIL] unsupported target {path}; tools moved to ChatToolFactory.kt")
    sys.exit(1)

required_imports = [
    "import kotlinx.serialization.json.JsonObject",
    "import kotlinx.serialization.json.buildJsonObject",
    "import kotlinx.serialization.json.contentOrNull",
    "import kotlinx.serialization.json.jsonPrimitive",
    "import kotlinx.serialization.json.put",
    "import kotlinx.serialization.json.putJsonObject",
    "import me.rerere.ai.core.InputSchema",
    "import me.rerere.ai.ui.UIMessagePart",
]

# Upstream reorganizes imports frequently; add only missing imports instead
# of depending on one exact contiguous import marker.
lines = text.splitlines(keepends=True)
last_import = max(
    (i for i, line in enumerate(lines) if line.startswith("import ")),
    default=-1,
)
if last_import < 0:
    print("[FAIL] no import section found in ChatToolFactory")
    sys.exit(1)

existing_imports = {line.strip() for line in lines if line.startswith("import ")}
missing_imports = [item for item in required_imports if item not in existing_imports]
if missing_imports:
    lines[last_import + 1:last_import + 1] = [item + "\n" for item in missing_imports]
    text = "".join(lines)

old = """        val mcpTools = mcpManager.getAllAvailableTools()
        val invalidNames = mcpTools
            .map { it.second }
            .distinct()
            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidNames.isNotEmpty()) {
            throw InvalidMcpServerNamesException(invalidNames)
        }
        mcpTools.forEach { (serverId, serverName, tool) ->
            add(
                Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = { tool.needsApproval },
                    execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                )
            )
        }
"""

new = r"""        val mcpTools = mcpManager.getAllAvailableTools()
        val invalidNames = mcpTools
            .map { it.second }
            .distinct()
            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidNames.isNotEmpty()) {
            throw InvalidMcpServerNamesException(invalidNames)
        }
        if (mcpTools.isNotEmpty()) {
            val byName = mcpTools.associate { (serverId, serverName, tool) ->
                "mcp__${serverName}__${tool.name}" to Triple(serverId, serverName, tool)
            }
            add(
                Tool(
                    name = "mcp_list_tools",
                    description = "List MCP tools available on this device. Call this first when you need APK/file/native capabilities. Use query to filter (apk, dex, native, file, patch).",
                    parameters = {
                        InputSchema.Obj(
                            properties = buildJsonObject {
                                putJsonObject("query") {
                                    put("type", "string")
                                    put("description", "Optional keyword to filter tools, e.g. apk")
                                }
                            },
                            required = emptyList()
                        )
                    },
                    execute = { args ->
                        val query = (args as? JsonObject)
                            ?.get("query")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.lowercase()
                            .orEmpty()
                        val lines = byName.entries
                            .filter { (name, triple) ->
                                query.isBlank() ||
                                    name.lowercase().contains(query) ||
                                    (triple.third.description ?: "").lowercase().contains(query)
                            }
                            .joinToString("\n") { (name, triple) ->
                                val tool = triple.third
                                val desc = (tool.description ?: "")
                                    .replace("\n", " ")
                                    .take(160)
                                val req = (tool.inputSchema as? InputSchema.Obj)
                                    ?.required
                                    ?.joinToString(",")
                                    ?: ""
                                "- $name | $desc | required=[$req]"
                            }
                        listOf(
                            UIMessagePart.Text(
                                lines.ifBlank { "No matching MCP tools." }
                            )
                        )
                    },
                )
            )
            add(
                Tool(
                    name = "mcp_call_tool",
                    description = "Execute one MCP tool. `tool` must be the exact name from mcp_list_tools.",
                    parameters = {
                        InputSchema.Obj(
                            properties = buildJsonObject {
                                putJsonObject("tool") {
                                    put("type", "string")
                                    put("description", "Exact name from mcp_list_tools")
                                }
                                putJsonObject("arguments") {
                                    put("type", "object")
                                    put("description", "Arguments object for that tool")
                                }
                            },
                            required = listOf("tool")
                        )
                    },
                    needsApproval = { args ->
                        val name = (args as? JsonObject)
                            ?.get("tool")
                            ?.jsonPrimitive
                            ?.contentOrNull
                        byName[name]?.third?.needsApproval == true
                    },
                    execute = { args ->
                        val obj = args as? JsonObject
                        val name = obj?.get("tool")?.jsonPrimitive?.contentOrNull
                        val found = byName[name]
                        if (found == null) {
                            listOf(
                                UIMessagePart.Text(
                                    "Unknown tool: $name. Call mcp_list_tools first."
                                )
                            )
                        } else {
                            val (serverId, _, tool) = found
                            val callArgs = (obj?.get("arguments") as? JsonObject)
                                ?: JsonObject(emptyMap())
                            mcpManager.callTool(serverId, tool.name, callArgs)
                        }
                    },
                )
            )
        }
"""

# The upstream API now accepts the assistant as an argument. Keep
# compatibility with both signatures while matching the surrounding block.
old_variants = [
    old,
    old.replace(
        "mcpManager.getAllAvailableTools()",
        "mcpManager.getAllAvailableTools(assistant)",
    ),
]

# ChatToolFactory moved the same block one nesting level deeper upstream.
old_candidates = old_variants + [
    "\n".join(("    " + line) if line else line for line in candidate.split("\n"))
    + "\n"
    for candidate in old_variants
]
matched_old = next((candidate for candidate in old_candidates if candidate in text), None)

if matched_old is None:
    print("[FAIL] MCP tools block not found in ChatToolFactory (upstream changed)")
    idx = text.find("getAllAvailableTools")
    print("context:", repr(text[idx:idx + 500] if idx >= 0 else "none"))
    sys.exit(1)

text = text.replace(matched_old, new, 1)
path.write_text(text, encoding="utf-8")
print(f"[OK] MCP lazy tools injected into {path}")
