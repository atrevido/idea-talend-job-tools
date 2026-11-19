package dev.talend.idea;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementFinder;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.impl.file.PsiPackageImpl;
import com.intellij.psi.search.DelegatingGlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Talend's physical routine sources need not be under an IDEA Java source root. */
public final class TalendRoutineFinder extends PsiElementFinder {
    private static final String PACKAGE = "routines";
    private static final String PREFIX = PACKAGE + ".";
    private static final Pattern FILE = Pattern.compile("(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)_[0-9]+(?:\\.[0-9]+)+\\.item");

    private final PsiManager psiManager;
    private final CachedValue<Map<String, List<VirtualFile>>> sources;

    public TalendRoutineFinder(Project project) {
        psiManager = PsiManager.getInstance(project);
        sources = CachedValuesManager.getManager(project).createCachedValue(() -> {
            Map<String, List<VirtualFile>> files = new HashMap<>();
            for (VirtualFile root : TalendLayout.projectsIn(project)) {
                VirtualFile directory = TalendLayout.routinesOf(root);
                if (directory != null) collect(directory, files);
            }
            return CachedValueProvider.Result.create(files, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    ProjectRootModificationTracker.getInstance(project));
        }, false);
    }

    static GlobalSearchScope scopeFor(XmlFile host) {
        var repository = TalendLayout.projectOf(host.getVirtualFile());
        // A standalone checkout doesn't supply Studio's remote project/branch references.
        // Restrict physical Talend routines, not the module's Java or library visibility.
        return new DelegatingGlobalSearchScope(host.getResolveScope(), repository) {
            @Override
            public boolean contains(@NotNull VirtualFile file) {
                if (!super.contains(file)) return false;
                if (!file.isInLocalFileSystem() || !"item".equals(file.getExtension())) return true;
                VirtualFile owner = TalendLayout.projectOf(file);
                if (owner == null || owner.equals(repository)) return true;
                VirtualFile routines = TalendLayout.routinesOf(owner);
                return routines == null || !VfsUtilCore.isAncestor(routines, file, true);
            }
        };
    }

    private static void collect(VirtualFile directory, Map<String, List<VirtualFile>> files) {
        for (VirtualFile child : directory.getChildren()) {
            ProgressManager.checkCanceled();
            if (child.is(VFileProperty.SYMLINK)) continue;
            if (child.isDirectory()) {
                collect(child, files);
            } else if ("item".equals(child.getExtension())) {
                var match = FILE.matcher(child.getName());
                if (match.matches()) files.computeIfAbsent(match.group(1), ignored -> new ArrayList<>()).add(child);
            }
        }
    }

    @Override
    public @Nullable PsiPackage findPackage(@NotNull String qualifiedName) {
        return PACKAGE.equals(qualifiedName) && !sources.getValue().isEmpty() ? new PsiPackageImpl(psiManager, PACKAGE) : null;
    }

    @Override
    public @Nullable PsiClass findClass(@NotNull String qualifiedName, @NotNull GlobalSearchScope scope) {
        PsiClass[] matches = findClasses(qualifiedName, scope);
        return matches.length == 1 ? matches[0] : null;
    }

    @Override
    public PsiClass @NotNull [] findClasses(@NotNull String qualifiedName, @NotNull GlobalSearchScope scope) {
        if (!qualifiedName.startsWith(PREFIX)) return PsiClass.EMPTY_ARRAY;
        String[] names = qualifiedName.substring(PREFIX.length()).split("\\.", -1);
        List<VirtualFile> candidates = sources.getValue().get(names[0]);
        if (candidates == null) return PsiClass.EMPTY_ARRAY;
        List<PsiClass> matches = new ArrayList<>(candidates.size());
        addClasses(names[0], candidates, scope, matches);
        if (names.length == 1) return matches.toArray(PsiClass.EMPTY_ARRAY);
        List<PsiClass> nested = new ArrayList<>(matches.size());
        for (PsiClass match : matches) {
            PsiClass current = match;
            for (int i = 1; current != null && i < names.length; i++) current = current.findInnerClassByName(names[i], false);
            if (current != null) nested.add(current);
        }
        return nested.toArray(PsiClass.EMPTY_ARRAY);
    }

    @Override
    public PsiClass @NotNull [] getClasses(@NotNull PsiPackage psiPackage, @NotNull GlobalSearchScope scope) {
        if (!PACKAGE.equals(psiPackage.getQualifiedName())) return PsiClass.EMPTY_ARRAY;
        List<PsiClass> matches = new ArrayList<>();
        sources.getValue().forEach((name, files) -> addClasses(name, files, scope, matches));
        return matches.toArray(PsiClass.EMPTY_ARRAY);
    }

    @Override
    public @NotNull Set<String> getClassNames(@NotNull PsiPackage psiPackage, @NotNull GlobalSearchScope scope) {
        if (!PACKAGE.equals(psiPackage.getQualifiedName())) return Set.of();
        Set<String> names = new HashSet<>();
        sources.getValue().forEach((name, files) -> {
            for (VirtualFile file : files) {
                if (scope.contains(file)) {
                    names.add(name);
                    break;
                }
            }
        });
        return names;
    }

    private void addClasses(String name, List<VirtualFile> files, GlobalSearchScope scope, List<PsiClass> matches) {
        for (VirtualFile file : files) {
            ProgressManager.checkCanceled();
            if (!scope.contains(file) || !(psiManager.findFile(file) instanceof PsiJavaFile javaFile)
                    || !PACKAGE.equals(javaFile.getPackageName())) continue;
            for (PsiClass candidate : javaFile.getClasses()) {
                if (name.equals(candidate.getName())) matches.add(candidate);
            }
        }
    }
}
