package dev.talend.idea;

import com.intellij.java.workspace.entities.JavaSourceRootPropertiesEntityModifications;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.JavaModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.intellij.platform.backend.workspace.VirtualFileUrls;
import com.intellij.platform.backend.workspace.WorkspaceModel;
import com.intellij.platform.backend.workspace.WorkspaceModelSerializerHook;
import com.intellij.platform.workspace.jps.entities.ContentRootEntity;
import com.intellij.platform.workspace.jps.entities.ContentRootEntityBuilder;
import com.intellij.platform.workspace.jps.entities.ContentRootEntityModifications;
import com.intellij.platform.workspace.jps.entities.InheritedSdkDependency;
import com.intellij.platform.workspace.jps.entities.ModuleEntity;
import com.intellij.platform.workspace.jps.entities.ModuleEntityModifications;
import com.intellij.platform.workspace.jps.entities.ModuleId;
import com.intellij.platform.workspace.jps.entities.ModuleSourceDependency;
import com.intellij.platform.workspace.jps.entities.ModuleTypeId;
import com.intellij.platform.workspace.jps.entities.SourceRootEntity;
import com.intellij.platform.workspace.jps.entities.SourceRootEntityBuilder;
import com.intellij.platform.workspace.jps.entities.SourceRootEntityModifications;
import com.intellij.platform.workspace.jps.entities.SourceRootTypeId;
import com.intellij.platform.workspace.storage.ExternalMappingKey;
import com.intellij.platform.workspace.storage.ImmutableEntityStorage;
import com.intellij.platform.workspace.storage.MutableEntityStorage;
import com.intellij.platform.workspace.storage.WorkspaceEntity;
import com.intellij.platform.workspace.storage.url.VirtualFileUrl;
import com.intellij.workspaceModel.ide.NonPersistentEntitySource;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.sequences.SequencesKt;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Supplies IDEA's native Java indexes with the original routine source files. */
public final class TalendRoutineRoots implements ProjectActivity {
    private static final ExternalMappingKey<Boolean> OWNED = ExternalMappingKey.Companion.create("dev.talend.idea.routineRoots");
    private static final SourceRootTypeId JAVA_SOURCE = new SourceRootTypeId("java-source");
    private static final ModuleTypeId JAVA_MODULE = new ModuleTypeId(JavaModuleType.JAVA_MODULE_ENTITY_TYPE_ID_NAME);
    private static final NonPersistentEntitySource TRANSIENT = NonPersistentEntitySource.INSTANCE;

    @Override
    public @NotNull Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        project.getService(RootUpdater.class).schedule();
        return Unit.INSTANCE;
    }

    @Service(Service.Level.PROJECT)
    public static final class RootUpdater implements Disposable {
        private final Project project;
        private final AtomicBoolean pending = new AtomicBoolean();
        private volatile boolean disposed;
        private volatile Set<String> candidates = Set.of();

        public RootUpdater(Project project) {
            this.project = project;
            var bus = project.getMessageBus().connect(this);
            bus.subscribe(ModuleRootListener.TOPIC, new ModuleRootListener() {
                @Override
                public void rootsChanged(@NotNull ModuleRootEvent event) {
                    schedule();
                }
            });
            bus.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                @Override
                public void after(@NotNull List<? extends VFileEvent> events) {
                    if (events.stream().anyMatch(RootUpdater.this::affectsLayout)) schedule();
                }
            });
        }

        private void schedule() {
            if (disposed || !pending.compareAndSet(false, true)) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                pending.set(false);
                if (!disposed && !project.isDisposed()) WriteAction.run(this::refresh);
            });
        }

        private boolean affectsLayout(VFileEvent event) {
            if (event instanceof VFileContentChangeEvent) return false;
            if (event instanceof VFilePropertyChangeEvent property && !property.isRename()) return false;
            VirtualFile file = event.getFile();
            boolean directory = switch (event) {
                case VFileCreateEvent create -> create.isDirectory();
                case VFileCopyEvent copy -> copy.getFile().isDirectory();
                default -> file != null && file.isDirectory();
            };
            if (event instanceof VFileMoveEvent move) {
                return relevantPath(move.getOldPath(), directory) || relevantPath(move.getNewPath(), directory);
            }
            if (event instanceof VFilePropertyChangeEvent rename) {
                return relevantPath(rename.getOldPath(), directory) || relevantPath(rename.getNewPath(), directory);
            }
            return relevantPath(event.getPath(), directory);
        }

        private boolean relevantPath(String path, boolean directory) {
            int slash = path.lastIndexOf('/');
            if (slash < 0) return false;
            String name = path.substring(slash + 1);
            if (TalendLayout.PROJECT_FILE.equals(name)) return true;
            if (!directory) return false;
            return candidates.contains(path) || candidates.contains(path.substring(0, slash))
                    || TalendLayout.CODE_DIRECTORY.equals(name) || TalendLayout.ROUTINES_DIRECTORY.equals(name);
        }

        private void refresh() {
            Set<String> paths = new HashSet<>();
            for (VirtualFile candidate : TalendLayout.candidateDirectories(project)) paths.add(candidate.getPath());
            candidates = Set.copyOf(paths);

            WorkspaceModel model = WorkspaceModel.getInstance(project);
            var snapshot = model.getCurrentSnapshot();
            var owned = snapshot.getExternalMapping(OWNED);
            Map<VirtualFileUrl, SourceRootEntity> previous = new HashMap<>();
            Map<VirtualFileUrl, ContentRootEntity> fallback = new HashMap<>();
            Set<ModuleEntity> obsoleteModules = new HashSet<>();
            for (var iterator = owned.getEntities(true).iterator(); iterator.hasNext(); ) {
                if (iterator.next() instanceof ModuleEntity module) obsoleteModules.add(module);
            }
            List<ContentRootEntity> content = new ArrayList<>();
            Set<VirtualFileUrl> explicit = new HashSet<>();
            for (var iterator = snapshot.entities(ContentRootEntity.class).iterator(); iterator.hasNext(); ) {
                ContentRootEntity root = iterator.next();
                if (owned.getDataByEntity(root.getModule()) == null) content.add(root);
                else fallback.putIfAbsent(root.getUrl(), root);
                for (SourceRootEntity source : root.getSourceRoots()) {
                    if (owned.getDataByEntity(source) != null) previous.put(source.getUrl(), source);
                    else explicit.add(source.getUrl());
                }
            }

            ProjectFileIndex index = ProjectFileIndex.getInstance(project);
            List<RoutineRoot> additions = new ArrayList<>();
            List<Checkout> newFallbacks = new ArrayList<>();
            for (VirtualFile talend : TalendLayout.projectsIn(project)) {
                var urls = model.getVirtualFileUrlManager();
                var checkoutUrl = VirtualFileUrls.toVirtualFileUrl(talend, urls);
                ContentRootEntity fallbackRoot = fallback.get(checkoutUrl);
                boolean uncovered = contentRoot(content, talend) == null;
                if (uncovered && fallbackRoot != null) obsoleteModules.remove(fallbackRoot.getModule());
                VirtualFile directory = TalendLayout.routinesOf(talend);
                if (directory == null) continue;
                var url = VirtualFileUrls.toVirtualFileUrl(directory, urls);
                ContentRootEntity parent = contentRoot(content, directory);
                boolean registerSource = !index.isExcluded(directory) && !explicit.contains(url);
                if (uncovered && fallbackRoot == null) {
                    newFallbacks.add(new Checkout(checkoutUrl, talend.getName(), parent == null && registerSource ? url : null));
                    if (parent == null) continue;
                }
                if (parent == null) parent = fallbackRoot;
                if (!registerSource || parent == null) continue;
                SourceRootEntity existing = previous.get(url);
                if (existing != null && Objects.equals(parent, existing.getContentRoot())) {
                    previous.remove(url);
                } else {
                    additions.add(new RoutineRoot(url, parent));
                }
            }
            if (previous.isEmpty() && obsoleteModules.isEmpty() && additions.isEmpty() && newFallbacks.isEmpty()) return;
            model.updateProjectModel("Update Talend routine source roots", storage -> {
                for (SourceRootEntity source : previous.values()) storage.removeEntity(source);
                TalendClasspathLibraries.removeOwnedFromModules(storage, obsoleteModules);
                for (ModuleEntity orphan : obsoleteModules) storage.removeEntity(orphan);
                for (RoutineRoot addition : additions) register(storage, addition);
                for (Checkout checkout : newFallbacks) registerFallback(storage, checkout);
                return Unit.INSTANCE;
            });
            TalendClasspathLibraries.restoreFallbacks(project);
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private record RoutineRoot(VirtualFileUrl url, ContentRootEntity contentRoot) { }
    private record Checkout(VirtualFileUrl url, String name, VirtualFileUrl sourceUrl) { }

    private static ContentRootEntity contentRoot(List<ContentRootEntity> roots, VirtualFile directory) {
        ContentRootEntity best = null;
        int bestLength = 0;
        for (ContentRootEntity root : roots) {
            VirtualFile folder = VirtualFileUrls.getVirtualFile(root.getUrl());
            if (folder != null && VfsUtilCore.isAncestor(folder, directory, false) && folder.getPath().length() > bestLength) {
                best = root;
                bestLength = folder.getPath().length();
            }
        }
        return best;
    }

    private static void register(MutableEntityStorage storage, RoutineRoot routine) {
        var source = SourceRootEntityModifications.createSourceRootEntity(routine.url(), JAVA_SOURCE, TRANSIENT);
        // Talend's folders are organizational: use package declarations, not folder paths.
        var properties = JavaSourceRootPropertiesEntityModifications.createJavaSourceRootPropertiesEntity(false, "routines", TRANSIENT);
        properties.setPackageMatchesDirectory(false);
        properties.setSourceRoot(source);

        ContentRootEntity content = storage.modifyEntity(ContentRootEntityBuilder.class, routine.contentRoot(), root -> {
            List<SourceRootEntityBuilder> sources = new ArrayList<>(root.getSourceRoots());
            sources.add(source);
            root.setSourceRoots(sources);
            return Unit.INSTANCE;
        });
        content.getSourceRoots().stream().filter(added -> routine.url().equals(added.getUrl())).findFirst()
                .ifPresent(added -> storage.getMutableExternalMapping(OWNED).addMapping(added, true));
    }

    private static void registerFallback(MutableEntityStorage storage, Checkout checkout) {
        String base = checkout.name() + " Talend Routines";
        String name = base;
        for (int i = 2; storage.contains(new ModuleId(name)); i++) name = base + " " + i;
        var module = ModuleEntityModifications.createModuleEntity(name,
                List.of(InheritedSdkDependency.INSTANCE, ModuleSourceDependency.INSTANCE), TRANSIENT);
        module.setType(JAVA_MODULE);
        var root = ContentRootEntityModifications.createContentRootEntity(checkout.url(), List.of(), TRANSIENT);
        module.setContentRoots(List.of(root));
        var added = storage.addEntity(module);
        storage.getMutableExternalMapping(OWNED).addMapping(added, true);
        if (checkout.sourceUrl() != null) {
            register(storage, new RoutineRoot(checkout.sourceUrl(), added.getContentRoots().getFirst()));
        }
    }

    /** NonPersistentEntitySource excludes .iml serialization; exclude these entities from the IDE cache too. */
    public static final class CacheFilter implements WorkspaceModelSerializerHook {
        @Override
        public @NotNull ImmutableEntityStorage beforeSerialization(@NotNull ImmutableEntityStorage storage) {
            var mapping = storage.getExternalMapping(OWNED);
            if (mapping.size() == 0) return storage;
            var copy = MutableEntityStorage.from(storage);
            List<WorkspaceEntity> entities = SequencesKt.toList(mapping.getEntities(true));
            TalendClasspathLibraries.removeOwnedFromModules(copy, entities);
            // Remove attached source roots first; deleting a module removes its remaining children.
            for (WorkspaceEntity entity : entities) if (entity instanceof SourceRootEntity) copy.removeEntity(entity);
            for (WorkspaceEntity entity : entities) if (entity instanceof ModuleEntity) copy.removeEntity(entity);
            return copy.toSnapshot();
        }
    }
}
