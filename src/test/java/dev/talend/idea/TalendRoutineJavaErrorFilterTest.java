package dev.talend.idea;

import com.intellij.core.JavaCoreApplicationEnvironment;
import com.intellij.core.JavaCoreProjectEnvironment;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.java.codeserver.highlighting.errors.JavaErrorKinds;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.light.LightPsiClassBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TalendRoutineJavaErrorFilterTest {
    @TempDir Path temp;

    @Test
    void suppressesOnlyTheFilenameDiagnosticExplainedByTalendVersioning() throws IOException {
        var disposable = Disposer.newDisposable();
        try {
            var app = new JavaCoreApplicationEnvironment(disposable, true);
            app.registerFileType(JavaFileType.INSTANCE, "item");
            var project = new JavaCoreProjectEnvironment(disposable, app).getProject();
            var psi = PsiManager.getInstance(project);
            var filter = new TalendRoutineJavaErrorFilter();
            Files.createFile(temp.resolve("talend.project"));

            var routine = javaFile(psi, app, "code/routines/sub/Payload_Bytes_10.23.item");
            var declaration = new LightPsiClassBuilder(routine, "Payload_Bytes");
            assertTrue(filter.shouldSuppressError(routine, JavaErrorKinds.CLASS_WRONG_FILE_NAME.create(declaration)));
            assertFalse(filter.shouldSuppressError(routine, JavaErrorKinds.CLASS_CLASHES_WITH_PACKAGE.create(declaration)));

            assertFilenameDiagnosticKept(filter, javaFile(psi, app, "code/routines/Other_0.1.item"), "Other_0");
            assertFilenameDiagnosticKept(filter, javaFile(psi, app, "code/routines/NoVersion.item"), "NoVersion");
            assertFilenameDiagnosticKept(filter, javaFile(psi, app, "process/Payload_0.1.item"), "Payload");
            assertFilenameDiagnosticKept(filter, javaFile(psi, app, "code/routines/Payload_0.1.java"), "Payload");
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private PsiJavaFile javaFile(PsiManager psi, JavaCoreApplicationEnvironment app, String path) throws IOException {
        var file = temp.resolve(path);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
        return (PsiJavaFile) psi.findFile(app.getLocalFileSystem().findFileByPath(file.toString()));
    }

    private static void assertFilenameDiagnosticKept(TalendRoutineJavaErrorFilter filter, PsiJavaFile file, String className) {
        var declaration = new LightPsiClassBuilder(file, className);
        assertFalse(filter.shouldSuppressError(file, JavaErrorKinds.CLASS_WRONG_FILE_NAME.create(declaration)));
    }
}
