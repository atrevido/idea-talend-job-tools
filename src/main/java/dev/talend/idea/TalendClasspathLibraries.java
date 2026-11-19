package dev.talend.idea;

import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.platform.backend.workspace.WorkspaceModel;
import com.intellij.platform.workspace.jps.entities.LibraryEntity;
import com.intellij.platform.workspace.jps.entities.LibraryTableId;
import com.intellij.platform.workspace.jps.entities.ModuleEntity;
import com.intellij.platform.workspace.jps.entities.ModuleId;
import com.intellij.platform.workspace.storage.MutableEntityStorage;
import com.intellij.platform.workspace.storage.WorkspaceEntity;
import com.intellij.workspaceModel.ide.NonPersistentEntitySource;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service(Service.Level.PROJECT)
@State(name = "TalendRoutineClasspath", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class TalendClasspathLibraries implements PersistentStateComponent<TalendClasspathLibraries.SavedImports> {
    private static final String LIBRARY_PREFIX = "Talend Routines compile classpath";
    private static final String OWNER_MARKER = " [dev.talend.idea:";

    // Real modules serialize their own library. Only fallback modules need workspace-local restoration data.
    private volatile SavedImports saved = new SavedImports();
    // Accessed only from the event thread by the import action.
    private final Set<String> importsInProgress = new HashSet<>();

    public static final class SavedImports {
        public List<CheckoutClasspath> checkouts = new ArrayList<>();
    }

    public static final class CheckoutClasspath {
        public String checkoutUrl = "";
        public String repository = "";
        public List<String> jarPaths = new ArrayList<>();

        public CheckoutClasspath() {}

        private CheckoutClasspath(String checkoutUrl, Path repository, List<Path> jars) {
            this.checkoutUrl = checkoutUrl;
            this.repository = repository.toString();
            this.jarPaths = jars.stream().map(repository::relativize).map(Path::toString).toList();
        }
    }

    @Override
    public @NotNull SavedImports getState() {
        return saved;
    }

    @Override
    public void loadState(@NotNull SavedImports state) {
        saved = state;
    }

    static Module sourceModule(Project project, VirtualFile checkout) {
        VirtualFile routines = TalendLayout.routinesOf(checkout);
        if (routines == null) return null;
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        if (!index.isInSourceContent(routines)) return null;
        VirtualFile source = index.getSourceRootForFile(routines);
        if (source == null) return null;

        Module owner = null;
        for (Module module : index.getModulesForFile(routines, true)) {
            if (module.isDisposed() || !Arrays.asList(ModuleRootManager.getInstance(module).getSourceRoots()).contains(source)) continue;
            if (owner != null) return null;
            owner = module;
        }
        return owner;
    }

    boolean beginImport(String checkoutPath) {
        return importsInProgress.add(checkoutPath);
    }

    void finishImport(String checkoutPath) {
        importsInProgress.remove(checkoutPath);
    }

    static boolean install(Project project, Module module, VirtualFile checkout, Path repository, List<Path> jars) {
        if (!putLibrary(module, checkout, libraryUrls(jars))) return false;
        TalendClasspathLibraries service = project.getService(TalendClasspathLibraries.class);
        var entry = isTransient(project, module) ? new CheckoutClasspath(checkout.getUrl(), repository, jars) : null;
        service.replaceSaved(checkout.getUrl(), entry);
        return true;
    }

    private void replaceSaved(String checkoutUrl, CheckoutClasspath entry) {
        SavedImports replacement = new SavedImports();
        for (CheckoutClasspath old : saved.checkouts) {
            if (!checkoutUrl.equals(old.checkoutUrl)) replacement.checkouts.add(old);
        }
        if (entry != null) replacement.checkouts.add(entry);
        saved = replacement;
    }

    static void restoreFallbacks(Project project) {
        if (!TrustedProjects.isProjectTrusted(project)) return;
        var service = project.getService(TalendClasspathLibraries.class);
        var application = ApplicationManager.getApplication();
        List<CheckoutClasspath> snapshot = service.saved.checkouts;
        if (snapshot.isEmpty()) return;
        application.executeOnPooledThread(() -> {
            var validated = new ArrayList<RestoredClasspath>(snapshot.size());
            for (CheckoutClasspath entry : snapshot) {
                if (project.isDisposed()) return;
                List<Path> jars = validatedJars(entry);
                if (jars != null) validated.add(new RestoredClasspath(entry, libraryUrls(jars)));
            }
            if (validated.isEmpty()) return;
            application.invokeLater(() -> {
                if (project.isDisposed() || !TrustedProjects.isProjectTrusted(project)) return;
                WriteAction.run(() -> {
                    for (RestoredClasspath restore : validated) {
                        CheckoutClasspath entry = restore.entry();
                        if (!service.saved.checkouts.contains(entry)) continue;
                        VirtualFile checkout = VirtualFileManager.getInstance().findFileByUrl(entry.checkoutUrl);
                        if (checkout == null || !checkout.isValid() || !checkout.isDirectory() || !TalendLayout.isProject(checkout)) continue;
                        Module module = sourceModule(project, checkout);
                        if (module != null && isTransient(project, module)) putLibrary(module, checkout, restore.jarUrls());
                    }
                });
            });
        });
    }

    private record RestoredClasspath(CheckoutClasspath entry, List<String> jarUrls) {}

    private static List<Path> validatedJars(CheckoutClasspath entry) {
        try {
            Path repository = Path.of(entry.repository);
            if (!repository.isAbsolute() || !repository.equals(repository.toRealPath()) || !Files.isDirectory(repository)) return null;
            List<Path> jars = new ArrayList<>(entry.jarPaths.size());
            for (String savedPath : entry.jarPaths) {
                Path relative = Path.of(savedPath);
                Path jar = repository.resolve(relative).normalize();
                if (relative.isAbsolute() || !jar.startsWith(repository) || !jar.equals(jar.toRealPath())
                        || !TalendMavenClasspath.isJarInRepository(jar, repository)) return null;
                jars.add(jar);
            }
            return jars;
        } catch (IOException | InvalidPathException invalid) {
            return null;
        }
    }

    private static List<String> libraryUrls(List<Path> jars) {
        return jars.stream().map(VfsUtil::getUrlForLibraryRoot).toList();
    }

    private static boolean isTransient(Project project, Module module) {
        ModuleEntity entity = WorkspaceModel.getInstance(project).getCurrentSnapshot().resolve(new ModuleId(module.getName()));
        return entity != null && entity.getEntitySource() == NonPersistentEntitySource.INSTANCE;
    }

    static void removeOwnedFromModules(MutableEntityStorage storage, Collection<? extends WorkspaceEntity> entities) {
        Set<ModuleId> modules = new HashSet<>();
        for (WorkspaceEntity entity : entities) {
            if (entity instanceof ModuleEntity module) modules.add(module.getSymbolicId());
        }
        if (modules.isEmpty()) return;
        // Module libraries are linked by ID, not Workspace Model children of the module.
        List<LibraryEntity> obsolete = new ArrayList<>();
        for (var iterator = storage.entities(LibraryEntity.class).iterator(); iterator.hasNext(); ) {
            LibraryEntity library = iterator.next();
            if (library.getEntitySource() == NonPersistentEntitySource.INSTANCE
                    && library.getName().startsWith(LIBRARY_PREFIX) && library.getName().contains(OWNER_MARKER)
                    && library.getTableId() instanceof LibraryTableId.ModuleLibraryTableId table && modules.contains(table.getModuleId())) {
                obsolete.add(library);
            }
        }
        for (LibraryEntity library : obsolete) storage.removeEntity(library);
    }

    private static boolean putLibrary(Module module, VirtualFile checkout, List<String> jarUrls) {
        String name = libraryName(module.getProject(), checkout);
        ModuleRootManager manager = ModuleRootManager.getInstance(module);
        List<LibraryOrderEntry> matches = findLibraries(manager.getOrderEntries(), name);
        if (matches.size() > 1) return false;
        LibraryOrderEntry current = matches.isEmpty() ? null : matches.getFirst();
        if (current != null && current.getScope() == DependencyScope.COMPILE
                && jarUrls.equals(Arrays.asList(current.getLibrary().getUrls(OrderRootType.CLASSES)))) return true;

        var roots = manager.getModifiableModel();
        try {
            List<LibraryOrderEntry> editable = findLibraries(roots.getOrderEntries(), name);
            LibraryOrderEntry previous = editable.isEmpty() ? null : editable.getFirst();
            var library = previous == null ? roots.getModuleLibraryTable().createLibrary(name) : previous.getLibrary();
            if (previous != null) previous.setScope(DependencyScope.COMPILE);
            var model = library.getModifiableModel();
            boolean committed = false;
            try {
                for (String url : model.getUrls(OrderRootType.CLASSES)) model.removeRoot(url, OrderRootType.CLASSES);
                for (String url : jarUrls) model.addRoot(url, OrderRootType.CLASSES);
                model.commit();
                committed = true;
            } finally {
                if (!committed) Disposer.dispose(model);
            }
            roots.commit();
            return true;
        } finally {
            if (!roots.isDisposed()) roots.dispose();
        }
    }

    private static List<LibraryOrderEntry> findLibraries(OrderEntry[] entries, String name) {
        List<LibraryOrderEntry> matches = new ArrayList<>(1);
        for (OrderEntry entry : entries) {
            if (entry instanceof LibraryOrderEntry library && library.isModuleLevel() && library.getLibrary() != null
                    && name.equals(library.getLibrary().getName())) matches.add(library);
        }
        return matches;
    }

    private static String libraryName(Project project, VirtualFile checkout) {
        Path path = Path.of(checkout.getPath());
        String identity = checkout.getUrl();
        String base = project.getBasePath();
        if (base != null) {
            Path projectRoot = Path.of(base);
            if (projectRoot.getRoot().equals(path.getRoot())) identity = projectRoot.relativize(path).toString();
        }
        UUID id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        return LIBRARY_PREFIX + OWNER_MARKER + id + "]";
    }
}
