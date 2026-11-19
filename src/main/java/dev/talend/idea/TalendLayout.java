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
    static final String PROJECT_FILE = "talend.project";
    static final String CODE_DIRECTORY = "code";
    static final String ROUTINES_DIRECTORY = "routines";

    private static boolean physicalDirectory(VirtualFile file) {
        return file != null && file.isDirectory() && !file.is(VFileProperty.SYMLINK);
    }

    private TalendLayout() {}

    static boolean isProject(VirtualFile directory) {
        VirtualFile marker = directory.findChild(PROJECT_FILE);
        return marker != null && !marker.isDirectory();
    }

    static VirtualFile projectOf(VirtualFile file) {
        for (VirtualFile directory = file.getParent(); directory != null; directory = directory.getParent()) {
            if (isProject(directory)) return directory;
        }
        return null;
    }

    static VirtualFile routinesOf(VirtualFile root) {
        VirtualFile code = root.findChild(CODE_DIRECTORY);
        if (!physicalDirectory(code)) return null;
        VirtualFile routines = code.findChild(ROUTINES_DIRECTORY);
        return physicalDirectory(routines) ? routines : null;
    }

    static VirtualFile routineProjectOf(VirtualFile file) {
        if (!file.isInLocalFileSystem() || file.isDirectory() || !"item".equals(file.getExtension()) || file.is(VFileProperty.SYMLINK)) {
            return null;
        }
        for (VirtualFile directory = file.getParent(); physicalDirectory(directory); directory = directory.getParent()) {
            if (ROUTINES_DIRECTORY.equals(directory.getName())) {
                VirtualFile code = directory.getParent();
                if (physicalDirectory(code) && CODE_DIRECTORY.equals(code.getName())) {
                    VirtualFile root = code.getParent();
                    if (physicalDirectory(root) && isProject(root)) return root;
                }
            }
            if (isProject(directory)) return null;
        }
        return null;
    }

    static Set<VirtualFile> projectsIn(Project project) {
        Set<VirtualFile> roots = new LinkedHashSet<>();
        for (VirtualFile directory : candidateDirectories(project)) {
            if (!physicalDirectory(directory) || !directory.isInLocalFileSystem()) continue;
            if (isProject(directory)) {
                roots.add(directory);
            } else {
                for (VirtualFile child : directory.getChildren()) {
                    if (physicalDirectory(child) && isProject(child)) roots.add(child);
                }
            }
        }
        return roots;
    }

    static Set<VirtualFile> candidateDirectories(Project project) {
        Set<VirtualFile> content = new LinkedHashSet<>();
        Collections.addAll(content, ProjectRootManager.getInstance(project).getContentRoots());
        String basePath = project.getBasePath();
        if (basePath != null) {
            VirtualFile base = LocalFileSystem.getInstance().findFileByPath(basePath);
            if (base != null) content.add(base);
        }
        return content;
    }
}
