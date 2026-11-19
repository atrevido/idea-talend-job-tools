package dev.talend.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerKeys;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Service(Service.Level.PROJECT)
public final class FragmentTab implements FileEditorManagerListener, Disposable {
    private static final String DRAFT_MESSAGE =
            "Talend draft · Apply to write back to the Job. Closing the last view without applying discards the draft.";

    private final Project project;
    private final ConcurrentHashMap<VirtualFile, Draft> drafts = new ConcurrentHashMap<>();

    public FragmentTab(Project project) {
        this.project = project;
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this);
    }

    static void open(XmlFile job, String label, FragmentXml target, FragmentJava java) {
        var project = job.getProject();
        var file = java.psi().getViewProvider().getVirtualFile();
        try {
            String extension = file.getFileType().getDefaultExtension();
            file.rename(FragmentTab.class, (job.getName() + " - " + label).replace('/', '·') + "." + extension);
        } catch (IOException failure) {
            java.close();
            throw new IllegalArgumentException("Could not name the Java fragment tab.", failure);
        }
        if (PsiManager.getInstance(project).findFile(file) != java.psi()) {
            java.close();
            throw new IllegalArgumentException("IDEA could not attach Java code insight to this fragment.");
        }
        var manager = FileEditorManager.getInstance(project);
        var tabs = project.getService(FragmentTab.class);
        tabs.drafts.put(file, new Draft(target, java));
        boolean opened = false;
        try {
            for (FileEditor editor : manager.openFile(file, true)) {
                if (isDraftEditor(editor, java)) {
                    opened = true;
                    return;
                }
            }
            throw new IllegalArgumentException("IDEA could not open this Talend fragment in the text editor.");
        } finally {
            if (!opened) {
                try {
                    manager.closeFile(file);
                } finally {
                    tabs.discard(file);
                }
            }
        }
    }

    private static boolean isDraftEditor(FileEditor editor, FragmentJava java) {
        return editor instanceof TextEditor text && text.isValid()
                && text.getEditor().getDocument() == java.document();
    }

    @Override
    public void fileClosed(@NotNull FileEditorManager manager, @NotNull VirtualFile file) {
        // A tab drag can close the source before asynchronously opening the destination.
        if (drafts.containsKey(file) && !Boolean.TRUE.equals(file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN))
                && !manager.isFileOpen(file)) {
            discard(file);
        }
    }

    private void discard(VirtualFile file) {
        var draft = drafts.remove(file);
        if (draft != null) draft.java.close();
    }

    @Override
    public void dispose() {
        for (VirtualFile file : drafts.keySet()) discard(file);
    }

    private void apply(VirtualFile file, Draft draft) {
        var manager = FileEditorManagerEx.getInstanceEx(project);
        if (draft.applied || drafts.get(file) != draft || !manager.isFileOpen(file)) return;
        String error;
        try {
            error = draft.target.apply(draft.java.editedText(project));
        } catch (IndexNotReadyException indexing) {
            error = "Wait for IDEA to finish indexing before applying this fragment.";
        } catch (IllegalArgumentException invalidText) {
            error = invalidText.getMessage();
        }
        if (error != null) {
            draft.message = error;
            EditorNotifications.getInstance(project).updateNotifications(file);
            return;
        }
        draft.applied = true;
        for (var window : manager.getWindows()) {
            if (!window.isDisposed()) manager.closeFile(file, window);
        }
    }

    private static final class Draft {
        private final FragmentXml target;
        private final FragmentJava java;
        private String message = DRAFT_MESSAGE;
        private boolean applied;

        private Draft(FragmentXml target, FragmentJava java) {
            this.target = target;
            this.java = java;
        }
    }

    public static final class NotificationProvider implements EditorNotificationProvider, DumbAware {
        @Override
        public @Nullable Function<? super FileEditor, ? extends JComponent> collectNotificationData(
                @NotNull Project project, @NotNull VirtualFile file) {
            var tabs = project.getServiceIfCreated(FragmentTab.class);
            var draft = tabs == null ? null : tabs.drafts.get(file);
            if (draft == null) return null;
            return editor -> {
                if (draft.applied || tabs.drafts.get(file) != draft || !isDraftEditor(editor, draft.java)) return null;
                var banner = new EditorNotificationPanel(editor);
                banner.setText(draft.message);
                banner.createActionLabel("Apply to Job", () -> ApplicationManager.getApplication().invokeLater(
                        () -> tabs.apply(file, draft), ModalityState.nonModal(), project.getDisposed()));
                return banner;
            };
        }
    }
}
