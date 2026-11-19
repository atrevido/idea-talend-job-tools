package dev.talend.idea;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class EditTalendJavaAction extends AnAction {
    private static final String TITLE = "Edit Talend Java";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(event.getData(CommonDataKeys.PSI_FILE) instanceof XmlFile file
                && file.isPhysical() && TalendFragments.job(file) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        if (!(event.getData(CommonDataKeys.PSI_FILE) instanceof XmlFile file)) return;
        Project project = file.getProject();
        PsiDocumentManager manager = PsiDocumentManager.getInstance(project);
        Document document = manager.getDocument(file);
        if (document == null) return;
        manager.commitDocument(document);
        XmlTag job = TalendFragments.job(file);
        if (job == null) return;
        List<TalendFragments.Fragment> fragments = TalendFragments.find(job);
        if (fragments.isEmpty()) {
            Messages.showInfoMessage(project, "No existing Java attributes were found on tJava, tJavaRow, or tMap components.", TITLE);
            return;
        }
        long stamp = document.getModificationStamp();
        Editor currentEditor = event.getData(CommonDataKeys.EDITOR);
        var chooser = JBPopupFactory.getInstance().createPopupChooserBuilder(fragments)
                .setTitle("Select a Talend Java Fragment")
                .setItemChosenCallback(fragment -> edit(file, job, document, stamp, fragment));
        if (currentEditor != null && currentEditor.getDocument() == document) {
            var selected = TalendFragments.atCaret(fragments, currentEditor.getCaretModel().getOffset());
            if (selected != null) chooser.setSelectedValue(selected, true);
        }
        chooser.createPopup().showInBestPositionFor(event.getDataContext());
    }

    private static void edit(XmlFile file, XmlTag job, Document document, long stamp, TalendFragments.Fragment fragment) {
        Project project = file.getProject();
        if (project.isDisposed()) return;
        if (!file.isValid() || !job.isValid() || !fragment.attribute().isValid() || document.getModificationStamp() != stamp) {
            Messages.showErrorDialog(project, "The Job changed. Reopen the fragment chooser.", TITLE);
            return;
        }
        try {
            FragmentXml target = new FragmentXml(file, document, fragment.attribute());
            FragmentTab.open(file, fragment.label(), target, new FragmentJava(file, job, fragment, target.text()));
        } catch (IllegalArgumentException exception) {
            Messages.showErrorDialog(project, exception.getMessage(), TITLE);
        }
    }
}
