#!/usr/bin/env python3
"""Inject MCP lazy tools (mcp_list_tools + mcp_call_tool) into ChatService.kt."""
from pathlib import Path
import sys

path = Path("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
if not path.exists():
    print(f"[FAIL] {path} not found")
    sys.exit(1)

text = path.read_text(encoding="utf-8")

if "mcp_list_tools" in text and "mcp_call_tool" in text:
    print("[SKIP] MCP lazy tools already present")
    sys.exit(0)

old_imp = "import kotlinx.serialization.json.jsonObject\nimport me.rerere.ai.core.MessageRole"
new_imp = """import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole"""

if old_imp not in text:
    print("[FAIL] import marker not found (upstream changed)")
    sys.exit(1)
text = text.replace(old_imp, new_imp, 1)

old = """                    mcpManager.getAllAvailableTools().also { allTools ->
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        add(
                            Tool(
                                name = "mcp__${serverName}__${tool.name}",
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = { tool.needsApproval },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
"""

new = r"""                    val mcpTools = mcpManager.getAllAvailableTools()
                    val invalidNames = mcpTools
                        .map { it.second }
                        .distinct()
                        .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                    if (invalidNames.isNotEmpty()) {
                        addError(
                            error = IllegalStateException(
                                context.getString(
                                    R.string.error_mcp_invalid_server_name,
                                    invalidNames.joinToString(", ")
                                )
                            ),
                            conversationId = conversationId,
                        )
                        return
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

if old not in text:
    print("[FAIL] MCP tools block not found (upstream changed)")
    idx = text.find("getAllAvailableTools")
    print("context:", repr(text[idx:idx+300] if idx >= 0 else "none"))
    sys.exit(1)

text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("[OK] MCP lazy tools injected into ChatService.kt")
