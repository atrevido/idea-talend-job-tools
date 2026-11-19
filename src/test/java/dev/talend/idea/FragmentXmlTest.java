package dev.talend.idea;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FragmentXmlTest {
    @Test
    void roundTripsMultilineJavaInADoubleQuotedAttribute() {
        String java = """
                if (count > 0 && count < 3) {
                \tString message = "path C:\\\\samples\\\\new says \\"hello\\"";
                \tSystem.out.println(message + '!');
                }
                """;
        String attribute = "if (count &gt; 0 &amp;&amp; count &lt; 3) {&#xA;"
                + "&#x9;String message = &quot;path C:\\\\samples\\\\new says \\&quot;hello\\&quot;&quot;;&#xA;"
                + "&#x9;System.out.println(message + '!');&#xA;}&#xA;";

        assertEquals(java, FragmentXml.decode(attribute));
        assertEquals(attribute, FragmentXml.encode(java, '"', false, true));
    }

    @Test
    void escapesOnlyTheAttributeQuoteAndDoesNotDecodeEntitiesRecursively() {
        String java = "char marker = '\\'';\r\n\tString sample = \"<tag> & &#10;\";";
        String attribute = "char marker = &apos;\\&apos;&apos;;&#13;&#10;&#9;"
                + "String sample = \"&lt;tag> &amp; &amp;#10;\";";

        assertEquals(java, FragmentXml.decode(attribute));
        assertEquals(attribute, FragmentXml.encode(java, '\'', true, false));
    }

    @Test
    void normalizesLiteralXmlWhitespaceButPreservesReferencedCharacters() {
        String supplementary = Character.toString(0x10437);
        String attribute = "one\r\ntwo\rthree\nfour\tfive&#13;&#xA;&#9;&#x10437;";

        assertEquals("one two three four five\r\n\t" + supplementary, FragmentXml.decode(attribute));
        assertEquals(supplementary, FragmentXml.encode(supplementary, '"', false, false));
    }

    @Test
    void rejectsMalformedReferencesAndCharactersOutsideXml10() {
        for (String invalid : List.of("&", "&unknown;", "&#;", "&#x;", "&#+1;", "&#xG;", "&#0;",
                "&#x1F;", "&#xD800;", "&#xFFFE;", "&#x110000;", "&#99999999999999999999;")) {
            assertThrows(IllegalArgumentException.class, () -> FragmentXml.decode(invalid), invalid);
        }
        for (int invalid : new int[]{0, 0x1F, 0xD800, 0xFFFE}) {
            String text = Character.toString(invalid);
            assertThrows(IllegalArgumentException.class, () -> FragmentXml.encode(text, '"', false, false),
                    () -> "U+" + Integer.toHexString(invalid));
        }
    }

    @Test
    void detectsMixedLineEndingsEvenWhenCrLfStraddlesReaderChunks() throws IOException {
        assertFalse(hasMixedLineEndingsInSingleCharacterChunks("first\r\nsecond\r\n"));
        assertFalse(hasMixedLineEndingsInSingleCharacterChunks("first\rsecond\r"));
        assertTrue(hasMixedLineEndingsInSingleCharacterChunks("first\r\nsecond\n"));
        assertTrue(hasMixedLineEndingsInSingleCharacterChunks("first\nsecond\r\n"));
        assertTrue(hasMixedLineEndingsInSingleCharacterChunks("first\r\nsecond\r"));
    }

    private static boolean hasMixedLineEndingsInSingleCharacterChunks(String text) throws IOException {
        // Force each CRLF pair to straddle read calls regardless of the scanner's buffer size.
        try (Reader reader = new StringReader(text) {
            @Override
            public int read(char[] buffer, int offset, int length) throws IOException {
                return super.read(buffer, offset, Math.min(length, 1));
            }
        }) {
            return FragmentXml.hasMixedLineEndings(reader);
        }
    }
}
