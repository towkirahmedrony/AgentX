#!/usr/bin/env python3
"""Reproducible prompt/tool token measurement for AgentX.

This is a developer tool only: it never runs on-device. It parses the Kotlin
sources that define the prompt sections (prompt/PromptDefaults.kt) and the tool
schemas (tools/*.kt) and reconstructs, as faithfully as a static parser can:

  * the assembled main-agent system prompt for a given runtime shape, and
  * the serialized tool schema JSON that the providers put on the wire
    ({"type":"function","function":{name,description,parameters}}).

Counts are reported two ways:
  * `chars/4`  - the character-based estimate the app itself uses.
  * `o200k`    - a real BPE tokenizer (tiktoken o200k_base) used as a proxy,
                 since the providers' exact tokenizers differ per model.

Everything the parser cannot see statically (runtime-injected values such as
AGENTS.md, the repo map or the todo list) is treated as absent unless a size is
supplied on the command line, so the numbers are a lower bound and always
reproducible.

Usage:
    python3 scripts/measure_prompt_tokens.py [--json]
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

ROOT = os.environ.get("AGENTX_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app/src/main/java/com/androidharness/app")
PROMPT_DEFAULTS = os.path.join(MAIN, "prompt/PromptDefaults.kt")
TOOLS_DIR = os.path.join(MAIN, "tools")
SKILLS_DIR = os.path.join(ROOT, "app/src/main/assets/skills")

STRING_RE = re.compile(r'"""(.*?)"""|"((?:[^"\\]|\\.)*)"', re.DOTALL)


def unescape_kotlin(raw: str) -> str:
    out = []
    i = 0
    while i < len(raw):
        ch = raw[i]
        if ch == "\\" and i + 1 < len(raw):
            nxt = raw[i + 1]
            mapping = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\", "$": "$", "'": "'"}
            if nxt == "u" and i + 5 < len(raw):
                out.append(chr(int(raw[i + 2 : i + 6], 16)))
                i += 6
                continue
            out.append(mapping.get(nxt, nxt))
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def concat_strings(expr: str) -> str:
    """Concatenate every string literal in a Kotlin expression."""
    parts = []
    for m in STRING_RE.finditer(expr):
        raw = m.group(1) if m.group(1) is not None else m.group(2)
        parts.append(unescape_kotlin(raw))
    return "".join(parts)


# --------------------------------------------------------------------------
# Prompt sections
# --------------------------------------------------------------------------

@dataclass
class Section:
    ident: str
    trigger: str
    default_text: str


def parse_sections() -> List[Section]:
    src = open(PROMPT_DEFAULTS, encoding="utf-8").read()
    # Each `private val NAME = PromptSection( ... )`
    sections: Dict[str, Section] = {}
    for m in re.finditer(r"private val (\w+) = PromptSection\(", src):
        name = m.group(1)
        start = m.end()
        # find matching closing paren at column 4 (`    )`)
        end = src.find("\n    )", start)
        body = src[start:end]
        idm = re.search(r'id = "([^"]+)"', body)
        trg = re.search(r"trigger = PromptTrigger\.(\w+)", body)
        dt = body.find("defaultText")
        # defaultText expression runs until the next `,\n        protection` /
        # `,\n        trigger` / end of body.
        tail = body[dt:]
        stop = len(tail)
        for marker in ["\n        protection", "\n        trigger", "\n        allowDisable", "\n        maxChars"]:
            idx = tail.find(marker)
            if idx != -1:
                stop = min(stop, idx)
        expr = tail[:stop]
        sections[name] = Section(
            ident=idm.group(1) if idm else name,
            trigger=trg.group(1) if trg else "ALWAYS",
            default_text=concat_strings(expr),
        )
    order_m = re.search(r"val MAIN_SECTIONS: List<PromptSection> = listOf\((.*?)\n    \)", src, re.DOTALL)
    order = re.findall(r"^\s*([A-Z_]+),", order_m.group(1), re.MULTILINE)
    return [sections[n] for n in order if n in sections]


# --------------------------------------------------------------------------
# Tools
# --------------------------------------------------------------------------

@dataclass
class Tool:
    name: str
    description: str
    param_descriptions: List[str] = field(default_factory=list)
    required: List[str] = field(default_factory=list)
    properties: List[str] = field(default_factory=list)


SCHEMA_CALL_RE = re.compile(r"Schema\.(?:string|integer|boolean|array)\(")
PROP_RE = re.compile(r'"(\w+)" to Schema\.')
REQUIRED_RE = re.compile(r"required = listOf\(([^)]*)\)")
NAME_RE = re.compile(r'override val name(?::\s*String)?\s*=\s*"([^"]+)"')

# Mirrors com.androidharness.app.tools.ToolGroups. Group membership decides
# which tools are sent up front under staged exposure.
GIT = {"git_show", "git_commit", "git_branch", "git_checkout", "git_push",
       "git_pull", "git_branch_manage"}
GIT_INSPECT = {"git_status", "git_diff", "git_log"}
NET = {"web_fetch", "web_search", "http_request"}
DIAGNOSTICS = {"env_status", "doctor", "read_logcat"}
PACKAGES = {"pkg_install", "pkg_search", "pkg_list"}
CODEGRAPH = {"codegraph_node", "codegraph_explore", "codegraph_impact",
             "codegraph_affected", "codegraph_sync"}
SKILLS_ADMIN = {"skills_list", "skill_manage"}
INITIAL_GROUPS = {"CORE", "NET"}


def group_of(name: str) -> str:
    if name in GIT:
        return "GIT"
    if name in NET:
        return "NET"
    if name in DIAGNOSTICS:
        return "DIAGNOSTICS"
    if name in PACKAGES:
        return "PACKAGES"
    if name in CODEGRAPH:
        return "CODEGRAPH"
    if name in SKILLS_ADMIN:
        return "SKILLS_ADMIN"
    if name.startswith("browser_"):
        return "BROWSER"
    return "CORE"


def iter_tool_blocks(src: str):
    """Yield (name, block) for each `override val name = "..."` region."""
    matches = list(NAME_RE.finditer(src))
    for i, m in enumerate(matches):
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(src)
        yield m.group(1), src[start:end]


def parse_tools() -> List[Tool]:
    tools: List[Tool] = []
    for fname in sorted(os.listdir(TOOLS_DIR)):
        if not fname.endswith(".kt"):
            continue
        src = open(os.path.join(TOOLS_DIR, fname), encoding="utf-8").read()
        for name, block in iter_tool_blocks(src):
            desc_m = re.search(r"override val description\s*=", block)
            if not desc_m:
                continue
            desc_expr = block[desc_m.end():]
            # description expression stops at the next `override val`/`override fun`.
            stop = re.search(r"\n\s*override (?:val|fun)", desc_expr)
            if stop:
                desc_expr = desc_expr[: stop.start()]
            description = concat_strings(desc_expr)
            # Parameters schema region
            ps = re.search(r"override val parametersSchema\s*=", block)
            region = block[ps.end():] if ps else ""
            stop2 = re.search(r"\n\s*override (?:val|fun)", region)
            if stop2:
                region = region[: stop2.start()]
            params = [unescape_kotlin(m.group(1) if m.group(1) is not None else m.group(2))
                      for m in STRING_RE.finditer(region)]
            # Every string literal in the parameters region that is NOT a
            # property name (`"name" to Schema...`) and NOT a bare JSON type
            # keyword is a parameter/description string. This captures array
            # item descriptions and array-level descriptions without having to
            # pair each schema node to its property.
            param_literals = []
            for lm in STRING_RE.finditer(region):
                tail = region[lm.end():lm.end() + 12]
                if re.match(r"\s*to\s", tail):
                    continue  # property name
                raw = lm.group(1) if lm.group(1) is not None else lm.group(2)
                value = unescape_kotlin(raw)
                if value in {"string", "integer", "boolean", "array", "object", "number"}:
                    continue
                param_literals.append(value)
            param_descs = param_literals
            props = PROP_RE.findall(region)
            req = []
            rm = REQUIRED_RE.search(region)
            if rm:
                req = re.findall(r'"([^"]+)"', rm.group(1))
            tools.append(Tool(name, description, param_descs, req, props))
    return tools


# --------------------------------------------------------------------------
# Composition (mirror of PromptFacts.matches)
# --------------------------------------------------------------------------

@dataclass
class Facts:
    workspace: str = "SHELL_READY"  # SSH | SHELL_READY | SHELL_TOYBOX | NO_SHELL
    shizuku: str = "ABSENT"
    full_access: bool = False
    plan: bool = False
    skills: bool = False
    agents_md: bool = False
    memory: bool = False
    memory_topics: bool = False
    todos: bool = False
    repo_map: bool = False
    browser_tools: bool = True
    workspace_path: str = "/data/data/com.androidharness.app/files/workspace"
    workspace_root: str = "/data/data/com.androidharness.app/files/workspace"
    values: Dict[str, str] = field(default_factory=dict)


def matches(trigger: str, f: Facts) -> bool:
    return {
        "ALWAYS": True,
        "WORKSPACE_SSH": f.workspace == "SSH",
        "WORKSPACE_SHELL_READY": f.workspace == "SHELL_READY",
        "WORKSPACE_SHELL_TOYBOX": f.workspace == "SHELL_TOYBOX",
        "WORKSPACE_NO_SHELL": f.workspace == "NO_SHELL",
        "NOT_SSH": f.workspace != "SSH",
        "SHIZUKU_GRANTED": f.workspace != "SSH" and f.shizuku == "GRANTED",
        "SHIZUKU_NO_PERMISSION": f.workspace != "SSH" and f.shizuku == "RUNNING_NO_PERMISSION",
        "SHIZUKU_NOT_RUNNING": f.workspace != "SSH" and f.shizuku == "NOT_RUNNING",
        "FULL_ACCESS": f.full_access and f.workspace != "SSH",
        "PLAN_MODE": f.plan,
        "BROWSER_AVAILABLE": f.browser_tools,
        "SKILLS_PRESENT": f.skills,
        "AGENTS_MD_PRESENT": f.agents_md,
        "MEMORY_PRESENT": f.memory,
        "MEMORY_TOPICS_PRESENT": f.memory_topics,
        "TODOS_PRESENT": f.todos,
        "REPO_MAP_PRESENT": f.repo_map,
    }.get(trigger, False)


PLACEHOLDER_RE = re.compile(r"\{\{([a-z_]+)\}\}")


def compose(sections: List[Section], f: Facts) -> Tuple[str, List[Tuple[str, int]]]:
    parts = []
    breakdown = []
    for s in sections:
        if not matches(s.trigger, f):
            continue
        text = s.default_text
        for ph in PLACEHOLDER_RE.findall(text):
            val = {
                "workspace_path": f.workspace_path,
                "workspace_root": f.workspace_root,
                "skills_index": f.values.get("skills_index", ""),
                "agents_md": f.values.get("agents_md", ""),
                "memory": f.values.get("memory", ""),
                "memory_topics": f.values.get("memory_topics", ""),
                "todos": f.values.get("todos", ""),
                "repository_map": f.values.get("repository_map", ""),
            }.get(ph, "")
            text = text.replace("{{" + ph + "}}", val)
        parts.append(text)
        breakdown.append((s.ident, len(text)))
    return "".join(parts), breakdown


def skills_index() -> str:
    if not os.path.isdir(SKILLS_DIR):
        return ""
    lines = []
    for name in sorted(os.listdir(SKILLS_DIR)):
        skill = os.path.join(SKILLS_DIR, name, "SKILL.md")
        if not os.path.isfile(skill):
            continue
        text = open(skill, encoding="utf-8").read()
        cat = re.search(r"^category:\s*(.+)$", text, re.MULTILINE)
        desc = re.search(r"^description:\s*(.+)$", text, re.MULTILINE)
        lines.append((cat.group(1).strip() if cat else "general",
                      name,
                      desc.group(1).strip() if desc else ""))
    grouped: Dict[str, List[Tuple[str, str]]] = {}
    for cat, name, desc in lines:
        grouped.setdefault(cat, []).append((name, desc))
    out = []
    for cat in sorted(grouped):
        out.append(f"  {cat}:")
        for name, desc in sorted(grouped[cat]):
            out.append(f"    - {name}: {desc}")
    return "\n".join(out) + "\n"


# --------------------------------------------------------------------------
# Measurement
# --------------------------------------------------------------------------

_ENCODING = None


def tokens(text: str) -> Tuple[int, int]:
    """Return (o200k_tokens, chars_over_4)."""
    global _ENCODING
    if _ENCODING is None:
        import tiktoken

        _ENCODING = tiktoken.get_encoding("o200k_base")
    return len(_ENCODING.encode(text)), len(text) // 4


def serialized_tools(tools: List[Tool]) -> str:
    """Approximate the provider wire JSON for the tools array."""
    arr = []
    for t in tools:
        props = {}
        for i, p in enumerate(t.properties):
            desc = t.param_descriptions[i] if i < len(t.param_descriptions) else ""
            props[p] = {"type": "string", "description": desc}
        params = {"type": "object", "properties": props}
        if t.required:
            params["required"] = t.required
        arr.append({"type": "function",
                    "function": {"name": t.name, "description": t.description, "parameters": params}})
    return json.dumps(arr, separators=(",", ":"), ensure_ascii=False)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    ap.add_argument("--workspace", default="SHELL_READY")
    ap.add_argument("--plan", action="store_true")
    ap.add_argument("--full-access", action="store_true")
    ap.add_argument("--no-skills", action="store_true")
    ap.add_argument("--browser", action="store_true", help="include browser tools")
    ap.add_argument("--mcp", type=int, default=0, help="number of extra MCP tools")
    args = ap.parse_args()

    sections = parse_sections()
    tools = parse_tools()
    if not args.browser:
        tools = [t for t in tools if not t.name.startswith("browser_")]
    staged_tools = [t for t in tools if group_of(t.name) in INITIAL_GROUPS]
    catalog = skills_index()

    facts = Facts(workspace=args.workspace, plan=args.plan, full_access=args.full_access,
                  skills=not args.no_skills)
    facts.values["skills_index"] = catalog if not args.no_skills else ""

    prompt, breakdown = compose(sections, facts)
    tool_json = serialized_tools(tools)
    staged_json = serialized_tools(staged_tools)

    sys_tok, sys_c4 = tokens(prompt)
    tool_tok, tool_c4 = tokens(tool_json)
    staged_tok, staged_c4 = tokens(staged_json)
    skills_text = ""
    if not args.no_skills:
        # the skills block is the mandatory + guidance + catalog sections
        ids = {"skills.mandatory", "skills.guidance", "skills.catalog"}
        skills_text = "".join(s.default_text for s in sections if s.ident in ids)
        for ph in ("{{skills_index}}",):
            skills_text = skills_text.replace(ph, catalog)
    sk_tok, sk_c4 = tokens(skills_text)

    total_tok = sys_tok + tool_tok
    total_c4 = sys_c4 + tool_c4

    result = {
        "prompt_chars": len(prompt),
        "prompt_tokens_o200k": sys_tok,
        "prompt_tokens_chars_div_4": sys_c4,
        "tool_count": len(tools),
        "tools_chars": len(tool_json),
        "tools_tokens_o200k": tool_tok,
        "tools_tokens_chars_div_4": tool_c4,
        "staged_tool_count": len(staged_tools),
        "staged_tools_tokens_o200k": staged_tok,
        "staged_tools_tokens_chars_div_4": staged_c4,
        "staged_total_input_tokens_o200k": sys_tok + staged_tok,
        "staged_total_input_tokens_chars_div_4": sys_c4 + staged_c4,
        "skills_chars": len(skills_text),
        "skills_tokens_o200k": sk_tok,
        "skills_tokens_chars_div_4": sk_c4,
        "total_input_tokens_o200k": total_tok,
        "total_input_tokens_chars_div_4": total_c4,
        "sections": [{"id": i, "chars": c} for i, c in breakdown],
    }
    if args.mcp:
        # MCP tools are opaque to this parser; estimate with a fixed average.
        avg = (tool_tok / max(1, len(tools)))
        result["mcp_tools_estimated_tokens_o200k"] = round(avg * args.mcp)

    if args.json:
        print(json.dumps(result, indent=2))
        return 0

    print(f"workspace={args.workspace} plan={args.plan} full_access={args.full_access} skills={not args.no_skills}")
    print("-" * 68)
    print(f"System prompt : {sys_tok:>6} tok (o200k)  {sys_c4:>6} tok (chars/4)  {len(prompt):>7} chars")
    print(f"Tool schemas  : {tool_tok:>6} tok (o200k)  {tool_c4:>6} tok (chars/4)  {len(tool_json):>7} chars  ({len(tools)} tools)")
    print(f"  skills block: {sk_tok:>6} tok (o200k)  {len(skills_text):>7} chars")
    print("-" * 68)
    print(f"TOTAL input   : {total_tok:>6} tok (o200k)  {total_c4:>6} tok (chars/4)")
    print(f"STAGED(CORE+GIT+NET): {sys_tok + staged_tok:>6} tok (o200k)  {sys_c4 + staged_c4:>6} tok (chars/4)  ({len(staged_tools)} tools)")
    print()
    grp: Dict[str, List[Tool]] = {}
    for t in tools:
        grp.setdefault(group_of(t.name), []).append(t)
    print("By group (o200k tokens sent only when the group is active):")
    for gname, ts in sorted(grp.items(), key=lambda kv: -tokens(serialized_tools(kv[1]))[0]):
        mark = "*" if gname in INITIAL_GROUPS else " "
        print(f"  {mark} {tokens(serialized_tools(ts))[0]:>5}  {len(ts):>2} tools  {gname}")
    if args.mcp:
        print(f"  + {args.mcp} MCP tools (estimated): {result['mcp_tools_estimated_tokens_o200k']} tok")
    print()
    print("Top tool schemas by size (o200k tokens):")
    sized = sorted(
        ((tokens(serialized_tools([t]))[0], t.name) for t in tools),
        reverse=True,
    )
    for tok, name in sized[:15]:
        print(f"  {tok:>5}  {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
