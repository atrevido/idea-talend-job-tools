package dev.talend.idea;

import com.intellij.find.findUsages.CustomUsageSearcher;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.find.findUsages.JavaClassFindUsagesOptions;
import com.intellij.find.findUsages.JavaFindUsagesOptions;
import com.intellij.find.findUsages.JavaMethodFindUsagesOptions;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStaticReferenceElement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.Processor;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Adds resolved, navigation-only Job occurrences without supplying refactoring references. */
public final class TalendJobUsageSearcher extends CustomUsageSearcher {
    @Override
    public void processElementUsages(@NotNull PsiElement element, @NotNull Processor<? super Usage> processor,
                                     @NotNull FindUsagesOptions options) {
        Target target = ReadAction.compute(() -> Target.create(element, options));
        if (target == null) return;
        SearchScope scope = options.searchScope;
        Collection<VirtualFile> candidates = ReadAction.compute(() -> candidates(target.name(), scope));
        for (VirtualFile file : candidates) {
            ProgressManager.checkCanceled();
            if (!ReadAction.compute(() -> processFile(file, target, scope, processor))) return;
        }
    }

    private static Collection<VirtualFile> candidates(String name, SearchScope scope) {
        if (scope instanceof LocalSearchScope local) return Arrays.asList(local.getVirtualFiles());
        if (!(scope instanceof GlobalSearchScope global)) return List.of();
        FileBasedIndex index = FileBasedIndex.getInstance();
        Collection<VirtualFile> escaped = index.getContainingFiles(TalendJobUsageIndex.ID, TalendJobUsageIndex.ESCAPED_NAME, global);
        Collection<VirtualFile> literal = index.getContainingFiles(TalendJobUsageIndex.ID, name, global);
        if (escaped.isEmpty()) return literal;
        if (literal.isEmpty()) return escaped;
        var combined = new LinkedHashSet<>(literal);
        combined.addAll(escaped);
        return combined;
    }

    private static boolean processFile(VirtualFile file, Target target, SearchScope scope, Processor<? super Usage> processor) {
        if (!target.declaration().isValid() || !file.isValid() || !"item".equals(file.getExtension())
                || !target.root().equals(TalendLayout.projectOf(file))) return true;
        PsiManager manager = target.declaration().getManager();
        if (!(manager.findFile(file) instanceof XmlFile xml)) return true;
        var job = TalendFragments.job(xml);
        if (job == null) return true;
        for (var fragment : TalendFragments.find(job)) {
            ProgressManager.checkCanceled();
            if (target.skipImports() && fragment.kind() == TalendFragments.Kind.IMPORTS) continue;
            XmlAttributeValue host = fragment.attribute().getValueElement();
            if (host == null) continue;
            FragmentUsageSource source;
            try {
                source = new FragmentUsageSource(host);
            } catch (IllegalArgumentException invalidAttribute) {
                continue;
            }
            String text = source.javaText();
            if (!text.contains(target.name()) && !text.contains(TalendJobUsageIndex.ESCAPED_NAME)) continue;
            var visitor = new Occurrences(target, source, xml, host, scope, processor);
            FragmentJava.createSearchPsi(xml, job, fragment, text).accept(visitor);
            if (visitor.stopped) return false;
        }
        return true;
    }

    private record Target(PsiElement declaration, PsiClass owner, String name, VirtualFile root, VirtualFile file,
                          boolean ordinary, boolean constructors, boolean overloads, boolean skipImports) {
        static Target create(PsiElement declaration, FindUsagesOptions options) {
            if (!declaration.isValid()) return null;
            PsiClass owner;
            String name;
            boolean constructors;
            boolean overloads = false;
            if (declaration instanceof PsiClass psiClass) {
                owner = psiClass;
                name = psiClass.getName();
                constructors = options instanceof JavaClassFindUsagesOptions java && java.isConstructorUsages;
                if (!options.isUsages && !constructors) return null;
            } else if (declaration instanceof PsiMethod method && options.isUsages) {
                owner = method.getContainingClass();
                name = method.getName();
                constructors = method.isConstructor();
                overloads = options instanceof JavaMethodFindUsagesOptions java && java.isIncludeOverloadUsages;
            } else {
                return null;
            }
            PsiFile containingFile = declaration.getContainingFile();
            if (owner == null || name == null || containingFile == null || !containingFile.isPhysical()) return null;
            VirtualFile file = containingFile.getVirtualFile();
            VirtualFile root = file == null ? null : TalendLayout.routineProjectOf(file);
            if (root == null) return null;
            boolean skipImports = options instanceof JavaFindUsagesOptions java && java.isSkipImportStatements;
            return new Target(declaration, owner, name, root, file, options.isUsages, constructors, overloads, skipImports);
        }

        boolean matches(PsiElement actual) {
            if (actual == null) return false;
            PsiFile actualFile = actual.getContainingFile();
            if (actualFile == null || !file.equals(actualFile.getVirtualFile())) return false;
            PsiManager manager = declaration.getManager();
            if (overloads && actual instanceof PsiMethod method && declaration instanceof PsiMethod searched) {
                return searched.isConstructor() == method.isConstructor() && name.equals(method.getName())
                        && manager.areElementsEquivalent(owner, method.getContainingClass());
            }
            return manager.areElementsEquivalent(declaration, actual);
        }

        boolean matches(JavaResolveResult result) {
            return result.isValidResult() && matches(result.getElement());
        }
    }

    private static final class Occurrences extends PsiRecursiveElementWalkingVisitor {
        private final Target target;
        private final FragmentUsageSource source;
        private final XmlFile xml;
        private final XmlAttributeValue host;
        private final SearchScope scope;
        private final Processor<? super Usage> processor;
        private boolean stopped;

        Occurrences(Target target, FragmentUsageSource source, XmlFile xml, XmlAttributeValue host,
                    SearchScope scope, Processor<? super Usage> processor) {
            this.target = target;
            this.source = source;
            this.xml = xml;
            this.host = host;
            this.scope = scope;
            this.processor = processor;
        }

        @Override
        public void visitElement(@NotNull PsiElement element) {
            ProgressManager.checkCanceled();
            if (target.constructors() && element instanceof PsiNewExpression expression) {
                var classRef = expression.getClassOrAnonymousClassReference();
                if (classRef != null && !expression.isArrayCreation()) {
                    PsiElement leaf = classRef.getReferenceNameElement();
                    if (named(leaf) && (target.declaration() instanceof PsiClass
                            ? !target.ordinary() && target.matches(classRef.advancedResolve(false))
                            : target.matches(expression.resolveMethodGenerics()))) report(leaf);
                }
            } else if (element instanceof PsiJavaCodeReferenceElement reference) {
                PsiElement leaf = reference.getReferenceNameElement();
                if (target.constructors() && target.declaration() instanceof PsiMethod
                        && reference instanceof PsiMethodReferenceExpression methodRef && methodRef.isConstructor()) {
                    if (leaf != null && target.matches(methodRef.advancedResolve(false))) report(leaf);
                } else if (target.ordinary() && named(leaf) && matches(reference)) {
                    report(leaf);
                }
            }
            if (!stopped) super.visitElement(element);
        }

        private boolean named(PsiElement leaf) {
            if (leaf == null) return false;
            String name = leaf.getText();
            return target.name().equals(name) || name.contains(TalendJobUsageIndex.ESCAPED_NAME);
        }

        private boolean matches(PsiJavaCodeReferenceElement reference) {
            if (!(reference instanceof PsiImportStaticReferenceElement)) return target.matches(reference.advancedResolve(false));
            // A single-static import can refer to an entire overload set.
            for (var candidate : reference.multiResolve(false)) {
                if (target.matches(candidate)) return true;
            }
            return false;
        }

        private void report(PsiElement leaf) {
            TextRange range = source.hostRange(leaf.getTextRange());
            if (scope instanceof LocalSearchScope local
                    && !local.containsRange(xml, range.shiftRight(host.getTextRange().getStartOffset()))) return;
            var info = new UsageInfo(host, range, false);
            if (!processor.process(new JobUsage(info))) {
                stopped = true;
                stopWalking();
            }
        }
    }

    private static final class JobUsage extends UsageInfo2UsageAdapter {
        JobUsage(UsageInfo info) {
            super(info);
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public void rename(@NotNull String newName) {
            throw new IncorrectOperationException("Edit this Java reference in its Talend Job fragment.");
        }
    }
}
