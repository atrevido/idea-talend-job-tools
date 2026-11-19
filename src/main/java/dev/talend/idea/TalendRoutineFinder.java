package dev.talend.idea;

import com.intellij.codeInsight.multiverse.CodeInsightContext;
import com.intellij.codeInsight.multiverse.ModuleContext;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.TestSourcesFilter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.ResolveScopeProvider;
import com.intellij.psi.search.DelegatingGlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopesCore;
import com.intellij.psi.search.ScopeOptimizer;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Keeps routines local to their Talend project without dropping normal Java dependencies. */
public final class TalendRoutineFinder extends ResolveScopeProvider implements ScopeOptimizer {
    @Override
    public @Nullable GlobalSearchScope getResolveScope(@NotNull VirtualFile file, @NotNull Project project) {
        return routineScope(file, null, project);
    }

    @Override
    public @Nullable GlobalSearchScope getResolveScope(@NotNull VirtualFile file, @NotNull CodeInsightContext context,
                                                       @NotNull Project project) {
        return routineScope(file, context instanceof ModuleContext moduleContext ? moduleContext.getModule() : null, project);
    }

    private static GlobalSearchScope routineScope(VirtualFile file, Module module, Project project) {
        VirtualFile root = TalendLayout.routineProjectOf(file);
        if (root == null) return null;
        if (module == null) module = ProjectFileIndex.getInstance(project).getModuleForFile(file);
        GlobalSearchScope base = module == null ? GlobalSearchScope.allScope(project)
                : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, TestSourcesFilter.isTestSources(file, project));
        return inTalendProject(base, root, project);
    }

    @Override
    public @Nullable GlobalSearchScope getRestrictedUseScope(@NotNull PsiElement element) {
        var source = element.getContainingFile();
        VirtualFile file = source == null ? null : source.getVirtualFile();
        VirtualFile root = file == null ? null : TalendLayout.routineProjectOf(file);
        // The native Java equivalence check treats same-FQN classes in distinct source files as equivalent.
        return root == null ? null : excludeForeignRoutines(GlobalSearchScope.everythingScope(element.getProject()), root);
    }

    static GlobalSearchScope scopeFor(XmlFile host) {
        GlobalSearchScope base = host.getResolveScope();
        VirtualFile file = host.getVirtualFile();
        VirtualFile root = file == null ? null : TalendLayout.projectOf(file);
        return root == null ? base : inTalendProject(base, root, host.getProject());
    }

    private static GlobalSearchScope inTalendProject(GlobalSearchScope base, VirtualFile root, Project project) {
        VirtualFile routines = TalendLayout.routinesOf(root);
        // The XML host and its routine sources can live in different IDEA modules.
        GlobalSearchScope visible = routines == null || base.contains(routines) ? base
                : base.uniteWith(GlobalSearchScopesCore.directoryScope(project, routines, true));
        return excludeForeignRoutines(visible, root);
    }

    private static GlobalSearchScope excludeForeignRoutines(GlobalSearchScope base, VirtualFile root) {
        return new DelegatingGlobalSearchScope(base, root) {
            @Override
            public boolean contains(@NotNull VirtualFile file) {
                if (!super.contains(file)) return false;
                VirtualFile owner = TalendLayout.routineProjectOf(file);
                return owner == null || root.equals(owner);
            }

            @Override
            public boolean isForceSearchingInLibrarySources() {
                return base.isForceSearchingInLibrarySources();
            }
        };
    }
}
