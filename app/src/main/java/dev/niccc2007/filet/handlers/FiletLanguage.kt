package dev.niccc2007.filet.handlers

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.util.Locale

/**
 * A syntax family: enough of a language to colour it correctly, and no more.
 *
 * Deliberately not a grammar. Filet needs "this is a string, that is a comment, those are
 * keywords" across thirty file types, and a TextMate grammar per type would mean shipping
 * and maintaining thirty grammar files to produce the same four colours. Where a real parse
 * matters - smali, in M6 - the handler supplies its own.
 */
data class Syntax(
    val keywords: Set<String>,
    val lineComment: String? = "//",
    val blockStart: String? = "/*",
    val blockEnd: String? = "*/",
    val stringQuotes: String = "\"'",
    /** `#` in shell and Python; kept separate so `#` in a C file is not a comment. */
    val hashComment: Boolean = false,
    val annotationPrefix: Char? = null,
    /** XML and HTML colour tags and attributes rather than keywords. */
    val markup: Boolean = false,
) {
    companion object {
        private val C_LIKE = setOf(
            "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
            "return", "class", "interface", "enum", "new", "this", "super", "null", "true", "false",
            "void", "int", "long", "float", "double", "char", "boolean", "byte", "short",
            "public", "private", "protected", "static", "final", "abstract", "try", "catch",
            "finally", "throw", "throws", "import", "package", "extends", "implements", "instanceof",
        )
        private val KOTLIN = C_LIKE + setOf(
            "fun", "val", "var", "when", "is", "as", "object", "companion", "data", "sealed",
            "suspend", "override", "internal", "lateinit", "by", "in", "out", "init", "constructor",
            "typealias", "inline", "reified", "operator", "infix", "vararg", "crossinline", "noinline",
        )
        private val PYTHON = setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue",
            "import", "from", "as", "with", "try", "except", "finally", "raise", "lambda", "pass",
            "None", "True", "False", "and", "or", "not", "in", "is", "global", "nonlocal", "yield", "async", "await",
        )
        private val LUA = setOf(
            "function", "end", "local", "if", "then", "else", "elseif", "for", "while", "do",
            "repeat", "until", "return", "break", "nil", "true", "false", "and", "or", "not", "in", "pairs", "ipairs",
        )
        private val SHELL = setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
            "function", "return", "export", "local", "in", "echo", "cd", "set", "unset", "source",
        )
        private val JS = C_LIKE + setOf(
            "function", "let", "const", "var", "async", "await", "yield", "typeof", "of", "export",
            "from", "undefined", "=>",
        )
        private val SMALI = setOf(
            ".class", ".super", ".method", ".end", ".field", ".line", ".locals", ".registers",
            ".param", ".prologue", ".annotation", ".implements", ".source",
            "invoke-virtual", "invoke-direct", "invoke-static", "invoke-super", "invoke-interface",
            "move-result", "move-result-object", "return-void", "return-object", "const-string",
            "const", "iget", "iput", "sget", "sput", "new-instance", "goto", "if-eqz", "if-nez",
        )

        /** Pick a family from the extension. Unknown means plain text, never a crash. */
        fun forExtension(ext: String): Syntax = when (ext.lowercase(Locale.US)) {
            "kt", "kts" -> Syntax(KOTLIN, annotationPrefix = '@')
            "java" -> Syntax(C_LIKE, annotationPrefix = '@')
            "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "swift" -> Syntax(C_LIKE)
            "js", "ts", "jsx", "tsx", "json" -> Syntax(JS)
            "py" -> Syntax(PYTHON, lineComment = "#", blockStart = null, blockEnd = null, hashComment = true, annotationPrefix = '@')
            "lua" -> Syntax(LUA, lineComment = "--", blockStart = "--[[", blockEnd = "]]")
            "sh", "bash", "zsh" -> Syntax(SHELL, lineComment = "#", blockStart = null, blockEnd = null, hashComment = true)
            "smali" -> Syntax(SMALI, lineComment = "#", blockStart = null, blockEnd = null, hashComment = true)
            "xml", "html", "htm", "svg" -> Syntax(emptySet(), lineComment = null, blockStart = "<!--", blockEnd = "-->", markup = true)
            "yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "gitignore" ->
                Syntax(emptySet(), lineComment = "#", blockStart = null, blockEnd = null, hashComment = true)
            "css", "scss" -> Syntax(emptySet(), lineComment = null)
            "sql" -> Syntax(setOf("select", "from", "where", "insert", "update", "delete", "create", "table", "index", "join", "on", "and", "or", "not", "null"), lineComment = "--")
            else -> Syntax(emptySet(), lineComment = null, blockStart = null, blockEnd = null)
        }

        fun isPlain(ext: String): Boolean = forExtension(ext).let {
            it.keywords.isEmpty() && it.lineComment == null && it.blockStart == null && !it.markup
        }
    }
}

/**
 * A hand-written highlighter on sora's [SimpleAnalyzeManager].
 *
 * Runs on sora's own analysis thread and checks [SimpleAnalyzeManager.Delegate.isCancelled]
 * every line, so typing in a 40,000-line file cancels the previous pass instead of queueing
 * forty of them.
 */
class FiletAnalyzer(private val syntax: Syntax) : SimpleAnalyzeManager<Unit>() {

    override fun analyze(text: StringBuilder, delegate: Delegate<Unit>): Styles {
        val builder = MappedSpans.Builder(512)
        var line = 0
        var column = 0
        var i = 0
        var inBlockComment = false
        val n = text.length

        fun span(style: Long) = builder.addIfNeeded(line, column, style)

        span(NORMAL)
        while (i < n) {
            if (delegate.isCancelled) break
            val c = text[i]

            if (c == '\n') {
                line++
                column = 0
                i++
                // Every line must start with a span or sora renders it unstyled.
                span(if (inBlockComment) COMMENT else NORMAL)
                continue
            }

            if (inBlockComment) {
                val end = syntax.blockEnd
                if (end != null && text.startsWith(end, i)) {
                    inBlockComment = false
                    i += end.length
                    column += end.length
                    span(NORMAL)
                } else {
                    i++
                    column++
                }
                continue
            }

            // block comment start
            val bs = syntax.blockStart
            if (bs != null && text.startsWith(bs, i)) {
                span(COMMENT)
                inBlockComment = true
                i += bs.length
                column += bs.length
                continue
            }

            // line comment
            val lc = syntax.lineComment
            if (lc != null && text.startsWith(lc, i)) {
                span(COMMENT)
                while (i < n && text[i] != '\n') { i++; column++ }
                continue
            }

            // string
            if (syntax.stringQuotes.indexOf(c) >= 0) {
                span(LITERAL)
                val quote = c
                i++; column++
                while (i < n && text[i] != '\n') {
                    if (text[i] == '\\' && i + 1 < n) { i += 2; column += 2; continue }
                    if (text[i] == quote) { i++; column++; break }
                    i++; column++
                }
                span(NORMAL)
                continue
            }

            // number
            if (c.isDigit()) {
                span(LITERAL)
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == 'x')) { i++; column++ }
                span(NORMAL)
                continue
            }

            // markup tag
            if (syntax.markup && c == '<') {
                span(HTML_TAG)
                while (i < n && text[i] != '>' && text[i] != '\n') { i++; column++ }
                if (i < n && text[i] == '>') { i++; column++ }
                span(NORMAL)
                continue
            }

            // identifier / keyword / annotation
            if (c.isLetter() || c == '_' || c == '.' || c == syntax.annotationPrefix) {
                val start = i
                val startCol = column
                if (c == syntax.annotationPrefix) { i++; column++ }
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-' ||
                        (text[i] == '.' && syntax.keywords.any { k -> k.startsWith(".") }))
                ) { i++; column++ }
                if (i == start) { i++; column++; continue }
                val word = text.substring(start, i)
                val style = when {
                    syntax.annotationPrefix != null && word.firstOrNull() == syntax.annotationPrefix -> ANNOTATION
                    word in syntax.keywords -> KEYWORD
                    word.lowercase(Locale.US) in syntax.keywords -> KEYWORD
                    else -> NORMAL
                }
                builder.addIfNeeded(line, startCol, style)
                if (style != NORMAL) span(NORMAL)
                continue
            }

            if (!c.isLetterOrDigit() && !c.isWhitespace()) {
                span(OPERATOR)
                i++; column++
                span(NORMAL)
                continue
            }

            i++
            column++
        }

        builder.determine(line)
        return Styles(builder.build())
    }

    private companion object {
        val NORMAL = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
        val KEYWORD = TextStyle.makeStyle(EditorColorScheme.KEYWORD, 0, true, false, false)
        val COMMENT = TextStyle.makeStyle(EditorColorScheme.COMMENT, 0, false, true, false)
        val LITERAL = TextStyle.makeStyle(EditorColorScheme.LITERAL)
        val OPERATOR = TextStyle.makeStyle(EditorColorScheme.OPERATOR)
        val ANNOTATION = TextStyle.makeStyle(EditorColorScheme.ANNOTATION)
        val HTML_TAG = TextStyle.makeStyle(EditorColorScheme.HTML_TAG)
    }
}

/** Wires the analyzer into sora. Everything else stays at sora's defaults. */
class FiletLanguage(private val syntax: Syntax) : EmptyLanguage() {
    private val manager = FiletAnalyzer(syntax)
    override fun getAnalyzeManager(): AnalyzeManager = manager
    override fun useTab(): Boolean = false
    override fun destroy() = manager.destroy()
}
