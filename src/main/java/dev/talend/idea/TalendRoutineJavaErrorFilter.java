package dev.talend.idea;

import com.intellij.java.codeserver.highlighting.JavaErrorFilter;
import com.intellij.java.codeserver.highlighting.errors.JavaCompilationError;
import com.intellij.java.codeserver.highlighting.errors.JavaErrorKinds;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

public final class TalendRoutineJavaErrorFilter implements JavaErrorFilter {
    private static final Pattern ITEM_VERSION = Pattern.compile("_[0-9]+\\.[0-9]+\\.item");

    @Override
    public boolean shouldSuppressError(@NotNull PsiFile file, @NotNull JavaCompilationError<?, ?> error) {
        if (error.kind() != JavaErrorKinds.CLASS_WRONG_FILE_NAME || !(error.psi() instanceof PsiClass declaration)) return false;
        var item = file.getVirtualFile();
        if (item == null || TalendLayout.routineProjectOf(item) == null) return false;

        // Studio generates Class.java from the source item Class_<major>.<minor>.item.
        String className = declaration.getName();
        String name = item.getName();
        return className != null && name.startsWith(className)
                && ITEM_VERSION.matcher(name).region(className.length(), name.length()).matches();
    }
}
