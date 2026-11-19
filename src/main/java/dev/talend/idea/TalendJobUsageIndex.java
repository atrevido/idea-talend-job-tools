package dev.talend.idea;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.java.syntax.element.JavaSyntaxTokenType;
import com.intellij.java.syntax.lexer.JavaLexer;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.ScalarIndexExtension;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.intellij.util.text.CharSequenceReader;
import org.jetbrains.annotations.NotNull;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.HashMap;
import java.util.Map;

/** Stores candidate names only. Indexing does not use PSI, resolution, or another index. */
public final class TalendJobUsageIndex extends ScalarIndexExtension<String> {
    static final ID<String, Void> ID = com.intellij.util.indexing.ID.create("dev.talend.idea.job.java.names");
    // A Java Unicode escape may spell part of an identifier without its literal name appearing.
    static final String ESCAPED_NAME = "\\u";

    @Override
    public @NotNull ID<String, Void> getName() {
        return ID;
    }

    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return content -> names(content.getContentAsText());
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override
    public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return file -> file.isInLocalFileSystem() && "item".equals(file.getExtension())
                && file.getFileType() == XmlFileType.INSTANCE;
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    static Map<String, Void> names(CharSequence text) {
        Map<String, Void> names = new HashMap<>();
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try {
            // A persisted UTF-8 BOM may survive in the indexing character sequence.
            CharSequence source = !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.subSequence(1, text.length()) : text;
            XMLStreamReader reader = factory.createXMLStreamReader(new CharSequenceReader(source));
            try {
                scan(reader, names);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException ignored) {
            // Keep candidates from well-formed fragments before a malformed/incomplete part of the file.
        }
        return names;
    }

    private static void scan(XMLStreamReader reader, Map<String, Void> names) throws XMLStreamException {
        int depth = 0;
        int jobDepth = 0;
        String component = null;
        boolean mapper = false;
        boolean table = false;
        while (reader.hasNext()) {
            ProgressManager.checkCanceled();
            int event = reader.next();
            if (event == XMLStreamConstants.DTD) return;
            if (event == XMLStreamConstants.END_ELEMENT) {
                if (depth == jobDepth) return;
                depth--;
                continue;
            }
            if (event != XMLStreamConstants.START_ELEMENT) continue;
            depth++;
            String name = reader.getLocalName();
            if (jobDepth == 0) {
                String namespace = reader.getNamespaceURI();
                if (depth == 1) {
                    if (TalendFragments.processRoot(name, namespace)) jobDepth = depth;
                    else if (!"XMI".equals(name) || !TalendFragments.XMI_NS.equals(namespace)) return;
                } else if (depth == 2 && "JobletProcess".equals(name) && TalendFragments.JOBLET_NS.equals(namespace)) {
                    jobDepth = depth;
                }
                continue;
            }
            if (depth == jobDepth + 1) {
                component = "node".equals(name) ? reader.getAttributeValue(null, "componentName") : null;
            } else if (depth == jobDepth + 2) {
                mapper = "tMap".equals(component)
                        && TalendFragments.mapperData(name, reader.getAttributeValue(TalendFragments.XSI_NS, "type"));
                if ("elementParameter".equals(name)
                        && TalendFragments.parameterKind(component, reader.getAttributeValue(null, "name")) != null) {
                    add(names, reader.getAttributeValue(null, "value"));
                }
            } else if (mapper && depth == jobDepth + 3) {
                table = TalendFragments.mapperTable(name);
                if (table) add(names, reader.getAttributeValue(null, "expressionFilter"));
            } else if (mapper && table && depth == jobDepth + 4 && "mapperTableEntries".equals(name)) {
                add(names, reader.getAttributeValue(null, "expression"));
            }
        }
    }

    private static void add(Map<String, Void> names, String source) {
        if (source == null || source.isEmpty()) return;
        if (source.contains(ESCAPED_NAME)) names.put(ESCAPED_NAME, null);
        var lexer = new JavaLexer(LanguageLevel.HIGHEST);
        lexer.start(source);
        while (lexer.getTokenType() != null) {
            ProgressManager.checkCanceled();
            if (lexer.getTokenType() == JavaSyntaxTokenType.IDENTIFIER) {
                names.put(source.substring(lexer.getTokenStart(), lexer.getTokenEnd()), null);
            }
            lexer.advance();
        }
    }
}
