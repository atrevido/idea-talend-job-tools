package dev.talend.idea;

import com.intellij.openapi.util.TextRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FragmentUsageSourceTest {
    @Test
    void navigatesToTheRawIdentifierAfterXmlEscapesAndNormalizedLineBreaks() {
        String attribute = "\"// &#x1F4A1;&#13;&#10;Helper.gr&#101;et(&quot;name&quot;);\"";
        var source = new FragmentUsageSource(attribute);
        assertEquals("// 💡\nHelper.greet(\"name\");", source.javaText());
        int start = source.javaText().indexOf("greet");
        assertEquals("gr&#101;et", source.hostRange(TextRange.from(start, "greet".length())).substring(attribute));
    }
}
