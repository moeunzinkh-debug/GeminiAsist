#!/usr/bin/env python3
"""
Extracts the JavaScript that GeminiAssist injects from Java string concatenations.

The injected scripts live in MainActivity.java as concatenated string literals, which
javac happily compiles even when the resulting JavaScript is broken. This script
rebuilds the exact runtime text so it can be syntax-checked (`node --check`) and
behaviour-checked (tools/js-selftest/run.js) *before* the app ships.

Usage:
    python3 tools/js-selftest/extract.py app/src/main/java/org/geminiassist/app/MainActivity.java <outdir>

Writes: BLOB_PATCH_JS.js, CLIPBOARD_PATCH_JS.js, AUTOFOCUS_JS.js,
        INJECT_PROMPT_JS.js, BLOB_DOWNLOAD.js
"""

import os
import re
import sys

CONSTANTS = [
    "BLOB_PATCH_JS",
    "CLIPBOARD_PATCH_JS",
    "AUTOFOCUS_JS",
    "GEMINI_INPUT_SELECTORS_JS",
]

# Java string escapes that can appear in the injected scripts.
UNESCAPE = {"n": "\n", "t": "\t", '"': '"', "\\": "\\", "'": "'"}

# Java keywords that must not be mistaken for a referenced constant.
NOT_A_CONSTANT = {
    "String", "var", "let", "function", "if", "else", "return", "new", "try",
    "catch", "finally", "this", "true", "false", "null", "int", "float",
}


def java_lits(expression):
    """Concatenated value of a Java expression made of string literals and +NAME+ refs."""
    parts = []
    i = 0
    n = len(expression)
    while i < n:
        c = expression[i]
        if c == '"':
            i += 1
            buf = []
            while i < n and expression[i] != '"':
                if expression[i] == "\\":
                    nxt = expression[i + 1]
                    buf.append(UNESCAPE.get(nxt, "\\" + nxt))
                    i += 2
                else:
                    buf.append(expression[i])
                    i += 1
            i += 1
            parts.append("".join(buf))
        elif c.isalpha() or c == "_":
            j = i
            while j < n and (expression[j].isalnum() or expression[j] == "_"):
                j += 1
            name = expression[i:j]
            if name not in NOT_A_CONSTANT:
                parts.append(("REF", name))
            i = j
        else:
            i += 1
    return parts


def extract_const(source, name):
    match = re.search(r"String " + name + r"\s*=\s*(.*?);\s*\n", source, re.S)
    if not match:
        raise SystemExit("could not find constant %s in MainActivity.java" % name)
    return java_lits(match.group(1))


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    src_path, out_dir = sys.argv[1], sys.argv[2]
    source = open(src_path, encoding="utf-8").read()
    os.makedirs(out_dir, exist_ok=True)

    constants = {name: extract_const(source, name) for name in CONSTANTS}
    selectors = "".join(x for x in constants["GEMINI_INPUT_SELECTORS_JS"])

    def resolve(parts, quoted_value='"sample text"'):
        """Drops in the selector constant and stand-ins for the Java-side variables.

        Injected scripts embed Java values (the quoted prompt, the escaped download URL,
        content disposition and mime type) - those become literal stand-ins here, which
        keeps the JavaScript structurally identical to what the app will run.
        """
        out = []
        for part in parts:
            if isinstance(part, tuple):
                if part[1] == "GEMINI_INPUT_SELECTORS_JS":
                    out.append(selectors)
                elif part[1] == "quoted":
                    out.append(quoted_value)
                else:
                    out.append("X")
            else:
                out.append(part)
        return "".join(out)

    for name in ("BLOB_PATCH_JS", "CLIPBOARD_PATCH_JS", "AUTOFOCUS_JS"):
        open(os.path.join(out_dir, name + ".js"), "w", encoding="utf-8").write(
            resolve(constants[name]))

    inject = re.search(
        r"private String buildInjectPromptJs\(String text\) \{.*?return (.*?);\n    \}",
        source, re.S)
    if not inject:
        raise SystemExit("could not find buildInjectPromptJs()")
    open(os.path.join(out_dir, "INJECT_PROMPT_JS.js"), "w", encoding="utf-8").write(
        resolve(java_lits(inject.group(1))))

    blob_download = re.search(
        r"chatWebView\.evaluateJavascript\(\s*(.*?),\s*\n\s*null\);", source, re.S)
    if not blob_download:
        raise SystemExit("could not find the blob download script")
    open(os.path.join(out_dir, "BLOB_DOWNLOAD.js"), "w", encoding="utf-8").write(
        resolve(java_lits(blob_download.group(1)), quoted_value="X"))

    print("extracted %d injected scripts to %s" % (len(CONSTANTS) + 1, out_dir))


if __name__ == "__main__":
    main()
