package dev.talend.idea;

import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ImportTalendRoutineClasspathAction extends DumbAwareAction {
    private static final String TITLE = "Import Talend Routine Classpath";
    private static final Path STUDIO_REPOSITORY = Path.of("TalendStudio-8", "studio", "configuration", ".m2", "repository");

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null || project.isDisposed()) return;
        if (!TrustedProjects.isProjectTrusted(project)) {
            error(project, "This action runs a local Maven executable. Trust the IDEA project before importing its classpath.");
            return;
        }

        VirtualFile checkout = selectedCheckout(project, event.getData(CommonDataKeys.VIRTUAL_FILE));
        if (checkout == null) {
            error(project, "Open a file in the intended Talend checkout and run the action again.");
            return;
        }
        Module module = TalendClasspathLibraries.sourceModule(project, checkout);
        if (module == null) {
            error(project, "No unique IDEA module owns this checkout's code/routines source root. Check the module's content and source roots.");
            return;
        }

        var dialog = new ImportDialog(project, module, checkout);
        if (!dialog.showAndGet()) return;
        Request request = dialog.request();
        queueImport(project, checkout, module, request);
    }

    private static VirtualFile selectedCheckout(Project project, VirtualFile current) {
        if (current != null && current.isValid() && current.isInLocalFileSystem()) {
            VirtualFile checkout = current.isDirectory() && TalendLayout.isProject(current) ? current : TalendLayout.projectOf(current);
            if (checkout != null) return checkout;
        }
        Set<VirtualFile> checkouts = TalendLayout.projectsIn(project);
        return checkouts.size() == 1 ? checkouts.iterator().next() : null;
    }

    private static void queueImport(Project project, VirtualFile checkout, Module module, Request request) {
        var libraries = project.getService(TalendClasspathLibraries.class);
        if (!libraries.beginImport(request.checkoutPath())) {
            error(project, "The Routine classpath for this checkout is already being imported.");
            return;
        }
        new Task.Backgroundable(project, TITLE, true) {
            private Path repository;
            private List<Path> jars;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Resolving Routine compile dependencies with offline Maven…");
                try {
                    repository = request.repository().toRealPath();
                    jars = TalendMavenClasspath.resolve(Path.of(request.checkoutPath()), request.maven(), repository,
                            request.javaHome(), indicator::checkCanceled);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ProcessCanceledException(interrupted);
                } catch (IOException failed) {
                    throw new UncheckedIOException(failed);
                }
                indicator.checkCanceled();
            }

            @Override
            public void onSuccess() {
                if (project.isDisposed()) return;
                String problem = WriteAction.compute(() -> {
                    if (!TrustedProjects.isProjectTrusted(project)) return "The project is no longer trusted.";
                    if (!checkout.isValid() || !request.checkoutPath().equals(checkout.getPath()) || !TalendLayout.isProject(checkout)
                            || module.isDisposed() || TalendClasspathLibraries.sourceModule(project, checkout) != module) {
                        return "The checkout or its Routine source module changed during import. Run the action again.";
                    }
                    return TalendClasspathLibraries.install(project, module, checkout, repository, jars) ? null
                            : "The module has duplicate Talend Routine classpath libraries. Remove the duplicate entries in Project Structure and retry.";
                });
                if (problem == null) {
                    Messages.showInfoMessage(project, "Imported the Routine compile classpath (" + jars.size()
                            + " JARs) into module “" + module.getName() + "”.", TITLE);
                } else {
                    error(project, problem);
                }
            }

            @Override
            public void onThrowable(@NotNull Throwable throwable) {
                if (project.isDisposed()) return;
                if (throwable instanceof UncheckedIOException failed) {
                    error(project, failed.getCause().getMessage());
                } else {
                    error(project, "The Routine compile classpath could not be resolved. See the IDE log for details.");
                    super.onThrowable(throwable);
                }
            }

            @Override
            public void onFinished() {
                libraries.finishImport(request.checkoutPath());
            }
        }.queue();
    }

    private static void error(Project project, String message) {
        Messages.showErrorDialog(project, message, TITLE);
    }

    private record Request(String checkoutPath, Path maven, Path repository, Path javaHome) {}

    private record SdkChoice(String label, Path home) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final class ImportDialog extends DialogWrapper {
        private final String checkoutPath;
        private final TextFieldWithBrowseButton maven = new TextFieldWithBrowseButton();
        private final TextFieldWithBrowseButton repository = new TextFieldWithBrowseButton();
        private final ComboBox<SdkChoice> sdk;
        private final JBLabel javaHome = new JBLabel();

        private ImportDialog(Project project, Module module, VirtualFile checkout) {
            super(project);
            checkoutPath = checkout.getPath();
            sdk = new ComboBox<>(sdkChoices(project, module));
            sdk.addActionListener(event -> updateJavaHome());
            updateJavaHome();

            maven.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile());
            maven.setText(defaultMaven());
            maven.getTextField().setColumns(32);
            repository.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir());
            repository.setText(defaultRepository().toString());
            setTitle(TITLE);
            setOKButtonText("Import");
            init();
        }

        private void updateJavaHome() {
            SdkChoice choice = (SdkChoice) sdk.getSelectedItem();
            javaHome.setText(choice == null ? "No configured Java SDK" : choice.home().toString());
        }

        @Override
        protected JComponent createCenterPanel() {
            return FormBuilder.createFormBuilder()
                    .addLabeledComponent("Talend checkout:", new JBLabel(checkoutPath))
                    .addLabeledComponent("Maven executable:", maven)
                    .addLabeledComponent("Local repository:", repository)
                    .addLabeledComponent("Java SDK:", sdk)
                    .addLabeledComponent("JAVA_HOME for Maven:", javaHome)
                    .addComponent(new JBLabel("Offline resolution only. This does not change IDEA's SDK settings."))
                    .getPanel();
        }

        @Override
        protected ValidationInfo doValidate() {
            Path executable = absolutePath(maven.getText());
            if (executable == null || !Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                return new ValidationInfo("Choose a local Maven executable.", maven);
            }
            Path localRepository = absolutePath(repository.getText());
            if (localRepository == null || !Files.isDirectory(localRepository)) {
                return new ValidationInfo("Choose an existing local Maven repository.", repository);
            }
            SdkChoice choice = (SdkChoice) sdk.getSelectedItem();
            if (choice == null || !Files.isExecutable(choice.home().resolve(SystemInfoRt.isWindows ? "bin/javac.exe" : "bin/javac"))) {
                return new ValidationInfo("Choose a local Java JDK configured in IDEA (JDK 17 for CDP).", sdk);
            }
            return null;
        }

        private Request request() {
            SdkChoice choice = (SdkChoice) sdk.getSelectedItem();
            return new Request(checkoutPath, absolutePath(maven.getText()), absolutePath(repository.getText()), choice.home());
        }
    }

    private static SdkChoice[] sdkChoices(Project project, Module module) {
        Map<Path, SdkChoice> choices = new LinkedHashMap<>();
        if (!ModuleRootManager.getInstance(module).isSdkInherited()) {
            addSdk(choices, ModuleRootManager.getInstance(module).getSdk(), "Module SDK: ");
        }
        addSdk(choices, ProjectRootManager.getInstance(project).getProjectSdk(), "Project SDK: ");
        for (Sdk sdk : ProjectJdkTable.getInstance(project).getAllJdks()) addSdk(choices, sdk, "");
        return choices.values().toArray(SdkChoice[]::new);
    }

    private static void addSdk(Map<Path, SdkChoice> choices, Sdk sdk, String prefix) {
        if (sdk == null || !(sdk.getSdkType() instanceof JavaSdk)) return;
        Path home = absolutePath(sdk.getHomePath());
        if (home != null) choices.putIfAbsent(home, new SdkChoice(prefix + sdk.getName(), home));
    }

    private static String defaultMaven() {
        String command = SystemInfoRt.isWindows ? "mvn.exe" : "mvn";
        String searchPath = System.getenv("PATH");
        if (searchPath != null) {
            for (String directory : searchPath.split(Pattern.quote(File.pathSeparator))) {
                Path path = absolutePath(directory);
                if (path == null) continue;
                Path candidate = path.resolve(command);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toString();
            }
        }
        if (SystemInfoRt.isMac) {
            for (String directory : List.of("/opt/homebrew/bin", "/usr/local/bin")) {
                Path candidate = Path.of(directory, command);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toString();
            }
        }
        return "";
    }

    private static Path defaultRepository() {
        Path home = Path.of(System.getProperty("user.home"));
        if (SystemInfoRt.isMac) {
            for (Path applications : List.of(Path.of("/Applications"), home.resolve("Applications"))) {
                Path studioCache = applications.resolve(STUDIO_REPOSITORY);
                if (Files.isDirectory(studioCache)) return studioCache;
            }
        }
        return home.resolve(".m2/repository");
    }

    private static Path absolutePath(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            Path path = Path.of(text.trim());
            return path.isAbsolute() ? path.normalize() : null;
        } catch (InvalidPathException invalid) {
            return null;
        }
    }
}
