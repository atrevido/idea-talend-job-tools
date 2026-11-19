package dev.talend.idea;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class TalendLayout {
    private TalendLayout() {}

    static boolean isProject(VirtualFile directory) {
        VirtualFile marker = directory.findChild("talend.project");
        return marker != null && !marker.isDirectory();
    }

    static VirtualFile projectOf(VirtualFile file) {
        for (VirtualFile directory = file.getParent(); directory != null; directory = directory.getParent()) {
            if (isProject(directory)) return directory;
        }
        return null;
    }

    static VirtualFile routinesOf(VirtualFile root) {
        return root.findFileByRelativePath("code/routines");
    }

    static Set<VirtualFile> projectsIn(Project project) {
        Set<VirtualFile> content = new LinkedHashSet<>();
        Collections.addAll(content, ProjectRootManager.getInstance(project).getContentRoots());
        String basePath = project.getBasePath();
        if (basePath != null) {
            VirtualFile base = LocalFileSystem.getInstance().findFileByPath(basePath);
            if (base != null) content.add(base);
        }
        Set<VirtualFile> roots = new LinkedHashSet<>();
        for (VirtualFile directory : content) {
            if (!directory.isDirectory()) continue;
            if (isProject(directory)) {
                roots.add(directory);
            } else {
                for (VirtualFile child : directory.getChildren()) {
                    if (child.isDirectory() && !child.is(VFileProperty.SYMLINK) && isProject(child)) roots.add(child);
                }
            }
        }
        return roots;
    }
}
