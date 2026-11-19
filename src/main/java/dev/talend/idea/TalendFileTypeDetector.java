package dev.talend.idea;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.util.io.ByteSequence;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Talend overloads .item: routines are raw Java, while Job and context files are XMI. */
public final class TalendFileTypeDetector implements FileTypeRegistry.FileTypeDetector {
    @Override
    public @Nullable FileType detect(@NotNull VirtualFile file, @NotNull ByteSequence prefix,
                                     @Nullable CharSequence text) {
        if (text == null || !file.isInLocalFileSystem() || !"item".equals(file.getExtension())) return null;
        VirtualFile root = TalendLayout.projectOf(file);
        if (root == null) return null;
        VirtualFile routines = TalendLayout.routinesOf(root);
        if (routines != null && VfsUtilCore.isAncestor(routines, file, true)) return JavaFileType.INSTANCE;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\uFEFF' && !Character.isWhitespace(c)) return c == '<' ? XmlFileType.INSTANCE : null;
        }
        return null;
    }
}
