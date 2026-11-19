package dev.talend.idea;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.FileResolveScopeProvider;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.JavaCodeFragment;
import com.intellij.psi.JavaCodeFragmentFactory;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SingleRootFileViewProvider;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.source.PsiJavaFileImpl;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.LightVirtualFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns the Java PSI and document, neither of which is backed by the actual Job. */
final class FragmentJava implements AutoCloseable {
    private final PsiFile psi;
    private final Document document;
    private final String original;
    private final String normalized;
    private final List<OriginalBreak> breaks = new ArrayList<>();

    FragmentJava(XmlFile host, XmlTag job, TalendFragments.Fragment fragment, String text) {
        original = text;
        normalized = StringUtil.convertLineSeparators(original);
        psi = createPsi(host, job, fragment, normalized);
        document = PsiDocumentManager.getInstance(host.getProject()).getDocument(psi);
        if (document == null) throw new IllegalStateException("The Java fragment has no editor document.");
        int offset = 0;
        for (int i = 0; i < original.length(); i++, offset++) {
            if (original.charAt(i) == '\r') {
                boolean crlf = i + 1 < original.length() && original.charAt(i + 1) == '\n';
                breaks.add(new OriginalBreak(document.createRangeMarker(offset, offset + 1), crlf ? "\r\n" : "\r"));
                if (crlf) i++;
            }
        }
    }

    static PsiFile createPsi(XmlFile host, XmlTag job, TalendFragments.Fragment fragment, String source) {
        if (fragment.kind() == TalendFragments.Kind.IMPORTS) {
            // The consuming CODE is in another attribute. Don't let the Java import
            // optimizer treat this independently editable import list as unused code.
            return PsiFileFactory.getInstance(host.getProject()).createFileFromText(
                    "TalendImports.txt", PlainTextFileType.INSTANCE.getLanguage(), source, true, false);
        }
        StringBuilder wrapper = new StringBuilder("import routines.*;\n");
        appendImports(wrapper, host, TalendFragments.parameterValue(fragment.component(), "IMPORT"));
        wrapper.append("class __TalendFragmentContext {\n");
        appendStruct(wrapper, "JobContext extends java.util.Properties", TalendFragments.context(job));
        wrapper.append("JobContext context;\n");
        wrapper.append("java.util.Map<java.lang.String, java.lang.Object> globalMap;\n");
        int rowIndex = 0;
        boolean flatMap = TalendFragments.sparkFlatMap(job, fragment.component());
        for (var row : TalendFragments.rows(job, fragment.component()).entrySet()) {
            String name = row.getKey();
            if (TalendFragments.identifier(name) && !name.equals("context") && !name.equals("globalMap")) {
                String type = "Row" + (++rowIndex);
                appendStruct(wrapper, type, row.getValue());
                if (flatMap && "outputList".equals(name)) {
                    // Talend's FLATMAP template provides a named subclass as a constructor
                    // convenience, but the list itself holds the generated base row type.
                    wrapper.append("class Output extends ").append(type).append(" implements java.io.Serializable {}\n");
                    wrapper.append("java.util.List<").append(type).append("> outputList;\n");
                } else {
                    wrapper.append(type).append(' ').append(name).append(";\n");
                }
            }
        }
        wrapper.append("void __talendFragmentAnchor() throws java.lang.Exception {\n");
        int anchorOffset = wrapper.length();
        wrapper.append(";\n}\n");
        if (fragment.kind() == TalendFragments.Kind.STATEMENTS) {
            String members = TalendFragments.parameterValue(fragment.component(), "CLASS_CODE");
            if (members != null) wrapper.append(StringUtil.convertLineSeparators(members)).append('\n');
        }
        wrapper.append("}\n");

        GlobalSearchScope scope = TalendRoutineFinder.scopeFor(host);
        var file = new LightVirtualFile("__TalendFragmentContext.java", JavaLanguage.INSTANCE, wrapper);
        file.setOriginalFile(host.getVirtualFile());
        var provider = new SingleRootFileViewProvider(PsiManager.getInstance(host.getProject()), file, false);
        var context = new ScopedJavaFile(provider, scope);
        provider.forceCachedPsi(context);
        PsiClass owner = context.getClasses()[0];
        PsiElement anchor = context.findElementAt(anchorOffset);
        var factory = JavaCodeFragmentFactory.getInstance(host.getProject());
        JavaCodeFragment psi = switch (fragment.kind()) {
            case STATEMENTS -> factory.createCodeBlockCodeFragment(source, anchor, true);
            case MEMBERS -> factory.createMemberCodeFragment(source, owner.getRBrace(), true);
            case EXPRESSION -> {
                String type = fragment.expectedType();
                PsiType expected = type == null ? null
                        : JavaPsiFacade.getElementFactory(host.getProject()).createTypeFromText(type, anchor);
                yield factory.createExpressionCodeFragment(source, anchor, expected, true);
            }
            case IMPORTS -> throw new AssertionError();
        };
        psi.forceResolveScope(scope);
        // Like the generated Job code, this fragment may propagate checked exceptions.
        psi.setExceptionHandler(exception -> true);
        return psi;
    }

    private static void appendImports(StringBuilder scope, XmlFile host, String imports) {
        if (imports == null || imports.isBlank()) return;
        var file = (PsiJavaFile) PsiFileFactory.getInstance(host.getProject()).createFileFromText(
                "TalendImports.java", JavaLanguage.INSTANCE, StringUtil.convertLineSeparators(imports), false, false);
        var importList = file.getImportList();
        if (importList == null) return;
        for (PsiImportStatementBase statement : importList.getAllImportStatements()) {
            if (PsiTreeUtil.findChildOfType(statement, PsiErrorElement.class) == null) {
                scope.append(statement.getText()).append('\n');
            }
        }
    }

    private static void appendStruct(StringBuilder scope, String typeDeclaration, Map<String, String> fields) {
        scope.append("static class ").append(typeDeclaration).append(" {\n");
        fields.forEach((column, columnType) -> scope.append(columnType).append(' ').append(column).append(";\n"));
        scope.append("}\n");
    }

    // Import statements anchored in the wrapper resolve in that file's scope,
    // which must match the scope of references in the editable fragment.
    private static final class ScopedJavaFile extends PsiJavaFileImpl implements FileResolveScopeProvider {
        private final GlobalSearchScope scope;

        private ScopedJavaFile(FileViewProvider provider, GlobalSearchScope scope) {
            super(provider);
            this.scope = scope;
        }

        @Override
        public GlobalSearchScope getFileResolveScope() {
            return scope;
        }

        @Override
        public boolean ignoreReferencedElementAccessibility() {
            return false;
        }
    }

    PsiFile psi() {
        return psi;
    }

    Document document() {
        return document;
    }

    String editedText(Project project) {
        PsiDocumentManager.getInstance(project).commitDocument(document);
        String text = document.getText();
        List<Replacement> replacements = new ArrayList<>();
        if (psi instanceof JavaCodeFragment java && !java.importsToString().isEmpty()) {
            // Java completion can add in-memory imports to code fragments. Persist their use as
            // qualified names instead of saving dangling short names or modifying a second attr.
            Set<String> imports = new HashSet<>(Arrays.asList(java.importsToString().split(",")));
            for (PsiJavaCodeReferenceElement reference : PsiTreeUtil.findChildrenOfType(psi, PsiJavaCodeReferenceElement.class)) {
                if (!reference.isQualified() && reference.getReferenceNameElement() != null &&
                    reference.resolve() instanceof PsiClass target && imports.contains(target.getQualifiedName())) {
                    replacements.add(new Replacement(reference.getReferenceNameElement().getTextRange(), target.getQualifiedName()));
                }
            }
        }
        if (replacements.isEmpty() && normalized.equals(text)) return original;
        for (OriginalBreak originalBreak : breaks) {
            RangeMarker marker = originalBreak.marker();
            if (marker.isValid() && marker.getEndOffset() == marker.getStartOffset() + 1 && text.charAt(marker.getStartOffset()) == '\n') {
                replacements.add(new Replacement(TextRange.create(marker.getStartOffset(), marker.getEndOffset()), originalBreak.text()));
            }
        }
        if (replacements.isEmpty()) return text;
        replacements.sort(Comparator.comparingInt((Replacement edit) -> edit.range().getStartOffset()).reversed());
        StringBuilder restored = new StringBuilder(text);
        for (Replacement edit : replacements) {
            restored.replace(edit.range().getStartOffset(), edit.range().getEndOffset(), edit.text());
        }
        return restored.toString();
    }

    @Override
    public void close() {
        for (OriginalBreak originalBreak : breaks) originalBreak.marker().dispose();
    }

    private record OriginalBreak(RangeMarker marker, String text) { }
    private record Replacement(TextRange range, String text) { }
}
