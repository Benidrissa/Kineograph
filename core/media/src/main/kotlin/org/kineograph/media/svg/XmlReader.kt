package org.kineograph.media.svg

import org.xmlpull.v1.XmlPullParser

private val TEXT_TAGS = setOf("text", "tspan", "textPath", "altGlyph")

/** Entities that HTML defines and XML does not. Hand-written and machine-written SVG both use them, and browsers cope. */
private val FRIENDLY_ENTITIES =
    mapOf(
        "nbsp" to " ",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "laquo" to "«",
        "raquo" to "»",
        "deg" to "°",
        "eacute" to "é",
        "egrave" to "è",
        "agrave" to "à",
        "ccedil" to "ç",
    )

/**
 * Turns the pull parser's events into a tree of [SvgElement].
 *
 * Namespace processing is switched off in the parser and done here instead. The parser would reject a file that writes
 * `xlink:href` without declaring the prefix, and such files are common and draw fine in browsers when embedded in HTML.
 *
 * The loop is iterative on purpose: a file that nests a hundred thousand groups must not be able to overflow the stack here.
 */
internal class SvgXmlReader(private val limits: SvgLimits, private val warnings: MutableSet<SvgWarning>, private val work: ParseWork) {
    private companion object {
        /** Deeper than any drawing, or any machine-made file. Only something written to cause trouble gets here. */
        const val ABANDON_DEPTH = 4096

        /** Far more than any stylesheet could want, and each extra class costs time on every rule. */
        const val MAX_CLASSES = 1024
    }

    /** One lookup, rather than a walk up every enclosing element, for each prefix of each attribute. A scope's changes are undone with it. */
    private val namespaces = HashMap<String, String>()
    private val namespaceUndo = ArrayList<Map<String, String?>?>()

    /** Text arrives in many small pieces in files with entities or comments, and joining them one at a time would take quadratic time. */
    private val pendingText = StringBuilder()
    private var pendingOwner: SvgElement? = null

    fun read(parser: XmlPullParser): SvgElement {
        var root: SvgElement? = null
        var current: SvgElement? = null
        var skipDepth = 0
        var keptDepth = 0
        for ((name, replacement) in FRIENDLY_ENTITIES) {
            // Optional politeness towards sloppy files. A parser that refuses is not a reason to fail the import.
            runCatching { parser.defineEntityReplacementText(name, replacement) }
        }
        reading@ while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> {
                    // The parser lets a file end with elements still open. That is a file cut short, and showing part of it as if it were
                    // the whole picture would be misleading.
                    if (current != null) {
                        throw SvgParseException(SvgParseException.Reason.MALFORMED, "The file ends before its elements are closed.")
                    }
                    break@reading
                }

                XmlPullParser.PROCESSING_INSTRUCTION -> {
                    if (parser.text?.trimStart()?.startsWith("xml-stylesheet") == true) {
                        warnings += SvgWarning.EXTERNAL_REFERENCE_IGNORED
                    }
                }

                XmlPullParser.START_TAG -> {
                    if (skipDepth > 0) {
                        skipDepth++
                        // The parser's own stack grows slowly for very deep files, enough to stall for minutes, so reading stops here.
                        if (skipDepth > ABANDON_DEPTH) {
                            warnings += SvgWarning.LIMIT_REACHED
                            break@reading
                        }
                        continue
                    }
                    current?.let { it.rawChildCount++ }
                    val scope = readNamespaceScope(parser)
                    val tag = svgLocalName(parser.name, scope)
                    if (root == null && tag != "svg") {
                        throw SvgParseException(SvgParseException.Reason.NOT_SVG, "The root element is <${parser.name}>, not <svg>.")
                    }
                    if (tag == null || shouldSkip(tag)) {
                        skipDepth = 1
                        continue
                    }
                    if (keptDepth >= limits.maxDepth || work.elementsKept >= limits.maxElements) {
                        warnings += SvgWarning.LIMIT_REACHED
                        skipDepth = 1
                        continue
                    }
                    // Only the first element of a nested picture can get here with nothing left, and then there is nothing to draw anyway.
                    if (!work.spend(WorkCost.ELEMENT_READ + parser.attributeCount * WorkCost.SELECTOR_STEP) && root != null) {
                        warnings += SvgWarning.LIMIT_REACHED
                        break@reading
                    }
                    work.elementsKept++
                    keptDepth++
                    pushNamespaces(scope)
                    val element = SvgElement(tag, current)
                    readAttributes(parser, element)
                    val parent = current
                    element.keepsText = tag == "style" || tag in TEXT_TAGS || (tag == "a" && parent?.keepsText == true)
                    if (parent == null) {
                        root = element
                    } else {
                        element.siblingIndex = parent.children.size
                        element.rawIndex = parent.rawChildCount - 1
                        parent.children += element
                        if (parent.keepsText) {
                            flushText()
                            contentOf(parent) += element
                        }
                    }
                    current = element
                }

                XmlPullParser.END_TAG -> {
                    if (skipDepth > 0) {
                        skipDepth--
                        continue
                    }
                    flushText()
                    popNamespaces()
                    keptDepth--
                    current = current?.parent
                    // Whatever follows the root is not part of the picture, and browsers would not show it either.
                    if (current == null) break@reading
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF, XmlPullParser.IGNORABLE_WHITESPACE -> {
                    val element = current
                    if (skipDepth == 0 && element != null && element.keepsText) appendText(element, parser.text)
                }
            }
        }
        flushText()
        return root ?: throw SvgParseException(SvgParseException.Reason.NOT_SVG, "The document has no root element.")
    }

    private fun contentOf(element: SvgElement): ArrayList<Any> = element.content ?: ArrayList<Any>(1).also { element.content = it }

    private fun appendText(element: SvgElement, text: String?) {
        if (text.isNullOrEmpty()) return
        if (pendingOwner !== element) flushText()
        pendingOwner = element
        pendingText.append(text)
    }

    private fun flushText() {
        val owner = pendingOwner ?: return
        if (pendingText.isNotEmpty()) contentOf(owner) += pendingText.toString()
        pendingText.setLength(0)
        pendingOwner = null
    }

    private fun shouldSkip(tag: String): Boolean =
        when (tag) {
            "script" -> {
                warnings += SvgWarning.SCRIPT_IGNORED
                true
            }

            "foreignObject" -> {
                warnings += SvgWarning.FOREIGN_OBJECT_IGNORED
                true
            }

            "meshgradient", "meshGradient", "hatch", "solidcolor" -> {
                warnings += SvgWarning.UNSUPPORTED_FEATURE_IGNORED
                true
            }

            // None of these draw anything. A filter only matters where something refers to it, and that is warned about there.
            "metadata", "title", "desc", "filter", "font", "font-face", "missing-glyph", "cursor", "view" -> {
                true
            }

            else -> {
                false
            }
        }

    private fun readNamespaceScope(parser: XmlPullParser): Map<String, String>? {
        var scope: HashMap<String, String>? = null
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)
            if (name == "xmlns" || name.startsWith("xmlns:")) {
                val map = scope ?: HashMap<String, String>(4).also { scope = it }
                map[if (name == "xmlns") "" else name.substring(6)] = parser.getAttributeValue(i)
            }
        }
        return scope
    }

    private fun lookupNamespace(prefix: String, scope: Map<String, String>?): String? = scope?.get(prefix) ?: namespaces[prefix]

    private fun pushNamespaces(scope: Map<String, String>?) {
        if (scope == null) {
            namespaceUndo += null
            return
        }
        val undo = HashMap<String, String?>(scope.size * 2)
        for ((prefix, uri) in scope) undo[prefix] = namespaces.put(prefix, uri)
        namespaceUndo += undo
    }

    private fun popNamespaces() {
        val undo = namespaceUndo.removeAt(namespaceUndo.lastIndex) ?: return
        for ((prefix, uri) in undo) {
            if (uri == null) namespaces.remove(prefix) else namespaces[prefix] = uri
        }
    }

    /** The element's local name if it belongs to SVG, or null for Inkscape, RDF and other foreign elements, which are skipped. */
    private fun svgLocalName(rawName: String, scope: Map<String, String>?): String? {
        val colon = rawName.indexOf(':')
        if (colon < 0) {
            val namespace = lookupNamespace("", scope)
            // A document with no namespace at all is technically not SVG, but its author plainly meant SVG.
            return if (namespace == null || namespace.isEmpty() || namespace == SVG_NAMESPACE) rawName else null
        }
        val prefix = rawName.substring(0, colon)
        val namespace = lookupNamespace(prefix, scope)
        return if (namespace == SVG_NAMESPACE || (namespace == null && prefix == "svg")) rawName.substring(colon + 1) else null
    }

    private fun readAttributes(parser: XmlPullParser, element: SvgElement) {
        val attributes = HashMap<String, String>(maxOf(4, parser.attributeCount * 2))
        var xlinkHref: String? = null
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)
            val value = parser.getAttributeValue(i)
            val colon = name.indexOf(':')
            if (colon < 0) {
                if (name == "xmlns") continue
                if (name.length > 2 && name.startsWith("on")) {
                    // Handlers can only run through a script engine, which this renderer does not have.
                    warnings += SvgWarning.SCRIPT_IGNORED
                    continue
                }
                attributes[name] = value
                continue
            }
            val prefix = name.substring(0, colon)
            val local = name.substring(colon + 1)
            if (prefix == "xmlns") continue
            val namespace = lookupNamespace(prefix, null)
            when {
                prefix == "xml" || namespace == XML_NAMESPACE -> if (local == "space") attributes["xml:space"] = value
                prefix == "xlink" || namespace == XLINK_NAMESPACE -> if (local == "href") xlinkHref = value
                namespace == SVG_NAMESPACE -> attributes[local] = value
            }
        }
        // SVG 2 prefers the plain attribute when a file has both.
        if (xlinkHref != null && "href" !in attributes) attributes["href"] = xlinkHref
        attributes["href"]?.let { href ->
            if (href.trimStart().startsWith("javascript:", ignoreCase = true)) {
                warnings += SvgWarning.SCRIPT_IGNORED
                attributes.remove("href")
            }
        }
        element.attrs = attributes
        element.id = attributes["id"]?.trim()?.takeIf { it.isNotEmpty() }
        attributes["class"]?.let { classes -> element.classes = readClasses(classes) }
    }

    /** One long attribute must not become millions of small strings, so this stops counting long before that. */
    private fun readClasses(text: String): List<String> {
        val out = LinkedHashSet<String>()
        var start = -1
        for (i in 0..text.length) {
            val isEnd = i == text.length || text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r'
            if (isEnd) {
                if (start >= 0) out += text.substring(start, i)
                start = -1
                if (out.size >= MAX_CLASSES) {
                    warnings += SvgWarning.LIMIT_REACHED
                    break
                }
            } else if (start < 0) {
                start = i
            }
        }
        return out.toList()
    }
}
