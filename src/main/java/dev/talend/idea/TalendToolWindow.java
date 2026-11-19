package dev.talend.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TalendToolWindow implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        Browser browser = new Browser(project);
        Content content = ContentFactory.getInstance().createContent(browser.panel, "", false);
        content.setDisposer(browser);
        content.setPreferredFocusableComponent(browser.tree);
        toolWindow.getContentManager().addContent(content);
        browser.refresh();
    }

    private record Location(Path file, int line) {}
    private record ItemRow(String label, Location source, Location callee) {}

    private static final class Browser implements Disposable {
        private final Project project;
        private final Tree tree = new Tree(new DefaultMutableTreeNode());
        private final JPanel panel = new JPanel(new BorderLayout());
        private final JButton refresh = new JButton("Refresh");
        private final JButton openSource = new JButton("Open source");
        private final JButton openCallee = new JButton("Open callee");
        private final JBLabel status = new JBLabel();
        private volatile boolean disposed;

        private Browser(Project project) {
            this.project = project;
            tree.setRootVisible(false);
            tree.setShowsRootHandles(true);
            tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
            tree.setCellRenderer(new ColoredTreeCellRenderer() {
                @Override
                public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected,
                                                  boolean expanded, boolean leaf, int row, boolean hasFocus) {
                    Object data = ((DefaultMutableTreeNode) value).getUserObject();
                    if (data instanceof ItemRow item) append(item.label());
                    else if (data instanceof String group) append(group);
                }
            });
            tree.getEmptyText().clear();
            tree.addTreeSelectionListener(event -> updateButtons());
            tree.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getButton() == MouseEvent.BUTTON1 && event.getClickCount() == 2) {
                        openDefault(row(tree.getPathForLocation(event.getX(), event.getY())));
                    }
                }
            });
            tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openTalendItem");
            tree.getActionMap().put("openTalendItem", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    openDefault(row(tree.getSelectionPath()));
                }
            });
            refresh.addActionListener(event -> refresh());
            openSource.addActionListener(event -> {
                ItemRow item = row(tree.getSelectionPath());
                if (item != null) open(item.source());
            });
            openCallee.addActionListener(event -> {
                ItemRow item = row(tree.getSelectionPath());
                if (item != null && item.callee() != null) open(item.callee());
            });
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
            actions.add(refresh);
            actions.add(openSource);
            actions.add(openCallee);
            actions.add(status);
            panel.add(actions, BorderLayout.NORTH);
            panel.add(new JBScrollPane(tree), BorderLayout.CENTER);
            updateButtons();
        }

        @Override
        public void dispose() {
            disposed = true;
        }

        private void refresh() {
            refresh.setEnabled(false);
            status.setText("Reading Talend catalog…");
            new Task.Backgroundable(project, "Reading Talend Catalog", true) {
                private DefaultMutableTreeNode modelRoot;

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    checkActive();
                    List<Path> roots = ReadAction.compute(() -> {
                        checkActive();
                        List<Path> paths = new ArrayList<>();
                        for (VirtualFile root : TalendLayout.projectsIn(project)) {
                            if (root.isInLocalFileSystem()) paths.add(root.toNioPath());
                        }
                        return paths;
                    });
                    modelRoot = buildTree(TalendCatalog.scan(roots, Browser.this::checkActive));
                }

                @Override
                public void onSuccess() {
                    if (closed()) return;
                    tree.setModel(new DefaultTreeModel(modelRoot));
                    for (int i = 0; i < modelRoot.getChildCount(); i++) {
                        DefaultMutableTreeNode repository = (DefaultMutableTreeNode) modelRoot.getChildAt(i);
                        tree.expandPath(new TreePath(repository.getPath()));
                    }
                    tree.getEmptyText().setText(modelRoot.getChildCount() == 0 ? "No talend.project found" : "");
                    status.setText("");
                    updateButtons();
                }

                @Override
                public void onCancel() {
                    if (!closed()) status.setText("Refresh cancelled");
                }

                @Override
                public void onThrowable(@NotNull Throwable error) {
                    if (!closed()) status.setText("Could not read the Talend catalog");
                }

                @Override
                public void onFinished() {
                    if (!closed()) refresh.setEnabled(true);
                }
            }.queue();
        }

        private boolean closed() {
            return disposed || project.isDisposed();
        }

        private void checkActive() {
            ProgressManager.checkCanceled();
            if (closed()) throw new ProcessCanceledException();
        }

        private void updateButtons() {
            ItemRow item = row(tree.getSelectionPath());
            openSource.setEnabled(item != null);
            openCallee.setEnabled(item != null && item.callee() != null);
        }

        private static ItemRow row(TreePath path) {
            if (path == null) return null;
            Object data = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            return data instanceof ItemRow item ? item : null;
        }

        private void openDefault(ItemRow item) {
            if (item != null) open(item.callee() == null ? item.source() : item.callee());
        }

        private void open(Location location) {
            Application application = ApplicationManager.getApplication();
            ModalityState modality = ModalityState.stateForComponent(panel);
            application.executeOnPooledThread(() -> {
                if (closed()) return;
                VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(location.file());
                application.invokeLater(() -> {
                    if (closed()) return;
                    if (file == null || !file.isValid() || file.isDirectory() || file.is(VFileProperty.SYMLINK)) {
                        status.setText("File unavailable. Refresh the catalog.");
                        return;
                    }
                    OpenFileDescriptor descriptor = location.line() < 0
                            ? new OpenFileDescriptor(project, file)
                            : new OpenFileDescriptor(project, file, location.line(), 0);
                    if (descriptor.canNavigate()) descriptor.navigate(true);
                }, modality);
            });
        }
    }

    private static DefaultMutableTreeNode buildTree(List<TalendCatalog.Repository> repositories) {
        DefaultMutableTreeNode modelRoot = new DefaultMutableTreeNode();
        for (TalendCatalog.Repository repository : repositories) {
            Path root = repository.root();
            DefaultMutableTreeNode projectNode = child(modelRoot,
                    root.getFileName() == null ? root.toString() : root.getFileName().toString());
            Map<TalendCatalog.Kind, DefaultMutableTreeNode> categories = new EnumMap<>(TalendCatalog.Kind.class);
            categories.put(TalendCatalog.Kind.JOB, child(projectNode, "Jobs"));
            categories.put(TalendCatalog.Kind.ROUTINE, child(projectNode, "Routines"));
            categories.put(TalendCatalog.Kind.CONTEXT, child(projectNode, "Contexts"));
            categories.put(TalendCatalog.Kind.TEST, child(projectNode, "Tests"));
            Map<Path, DefaultMutableTreeNode> groups = new HashMap<>();
            for (TalendCatalog.Entry entry : repository.entries()) {
                DefaultMutableTreeNode parent = categories.get(entry.kind());
                Path relative = root.relativize(entry.item());
                int firstFolder = switch (entry.kind()) {
                    case JOB -> 0;
                    case TEST, CONTEXT -> 1;
                    case ROUTINE -> 2;
                };
                Path folder = root;
                for (int i = 0; i < relative.getNameCount() - 1; i++) {
                    Path part = relative.getName(i);
                    folder = folder.resolve(part);
                    if (i < firstFolder) continue;
                    DefaultMutableTreeNode group = groups.get(folder);
                    if (group == null) {
                        group = child(parent, part.toString());
                        groups.put(folder, group);
                    }
                    parent = group;
                }
                Location source = new Location(entry.item(), -1);
                DefaultMutableTreeNode item = child(parent, new ItemRow(label(entry), source, null));
                List<TalendCatalog.Call> calls = repository.callsByItem().get(entry.item());
                if (calls == null) continue;
                for (TalendCatalog.Call call : calls) {
                    Location callee = call.target() == null ? null : new Location(call.target().item(), -1);
                    child(item, new ItemRow(label(call), new Location(entry.item(), call.line()), callee));
                }
            }
        }
        return modelRoot;
    }

    private static String label(TalendCatalog.Entry entry) {
        return entry.label() + " (" + entry.version() + ")";
    }

    private static String label(TalendCatalog.Call call) {
        String target = call.process().isBlank() ? "job" : call.process();
        if (!call.version().isBlank()) target += " (" + call.version() + ")";
        String details = switch (call.resolution()) {
            case RESOLVED -> label(call.target());
            case UNRESOLVED -> target + " — unresolved";
            case AMBIGUOUS -> target + " — ambiguous";
            case DYNAMIC -> target + " — dynamic";
        };
        return call.component() + " → " + details;
    }

    private static DefaultMutableTreeNode child(DefaultMutableTreeNode parent, Object data) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(data);
        parent.add(node);
        return node;
    }
}
