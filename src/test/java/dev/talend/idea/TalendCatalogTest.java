package dev.talend.idea;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.talend.idea.TalendCatalog.Kind.JOB;
import static dev.talend.idea.TalendCatalog.Resolution.AMBIGUOUS;
import static dev.talend.idea.TalendCatalog.Resolution.DYNAMIC;
import static dev.talend.idea.TalendCatalog.Resolution.RESOLVED;
import static dev.talend.idea.TalendCatalog.Resolution.UNRESOLVED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TalendCatalogTest {
    @TempDir
    Path temp;

    @Test
    void resolvesExactAndLatestByPropertyIdRatherThanXmiIdOrLabel() throws IOException {
        Path root = repository("first");
        Path older = job(root, "process/Target_1.9", "fixture-target", "1.9", "fixture-target", "");
        Path newer = job(root, "process/Target_1.10", "fixture-target", "1.10", "fixture-property", "");
        Path caller = job(root, "process/Caller_1.0", "fixture-caller", "1.0", "fixture-property",
                call("exact", "fixture-target", "1.9", "false")
                        + call("defaultLatest", "fixture-target", null, null)
                        + call("explicitLatest", "fixture-target", "Latest", null)
                        + call("unknownVersion", "fixture-target", "1.11", null)
                        + call("xmiId", "fixture-property", "1.10", null)
                        + call("missingId", null, "1.10", null));

        TalendCatalog.Repository catalog = scan(root);
        TalendCatalog.Entry oldTarget = new TalendCatalog.Entry(JOB, older, "fixture-target", "Fixture Target", "1.9");
        TalendCatalog.Entry newTarget = new TalendCatalog.Entry(JOB, newer, "fixture-target", "Fixture Target", "1.10");
        assertTrue(catalog.entries().containsAll(List.of(oldTarget, newTarget)));
        List<TalendCatalog.Call> calls = catalog.callsByItem().get(caller);
        assertEquals(List.of("exact", "defaultLatest", "explicitLatest", "unknownVersion", "xmiId", "missingId"),
                calls.stream().map(TalendCatalog.Call::component).toList());
        assertEquals(List.of("1.9", "", "Latest", "1.11", "1.10", "1.10"),
                calls.stream().map(TalendCatalog.Call::version).toList());
        assertEquals(List.of(oldTarget, newTarget, newTarget),
                calls.subList(0, 3).stream().map(TalendCatalog.Call::target).toList());
        assertEquals(List.of(RESOLVED, RESOLVED, RESOLVED, UNRESOLVED, UNRESOLVED, UNRESOLVED),
                calls.stream().map(TalendCatalog.Call::resolution).toList());
        assertTrue(calls.subList(3, 6).stream().allMatch(call -> call.target() == null));
    }

    @Test
    void leavesAmbiguousAndDynamicCallsWithoutTargets() throws IOException {
        Path root = repository("first");
        job(root, "process/one/Target_1.2", "fixture-target", "1.2", "fixture-property", "");
        job(root, "process/two/Target_1.2", "fixture-target", "1.2", "fixture-property", "");
        Path unique = job(root, "process/Target_1.1", "fixture-target", "1.1", "fixture-property", "");
        Path caller = job(root, "process/Caller_1.0", "fixture-caller", "1.0", "fixture-property",
                call("duplicateExact", "fixture-target", "1.2", null)
                        + call("duplicateLatest", "fixture-target", null, null)
                        + call("dynamic", "fixture-target", "1.1", "true")
                        + call("computedDynamic", "fixture-target", "1.1", "settings.chooseTarget")
                        + call("uniqueExact", "fixture-target", "1.1", "false"));

        List<TalendCatalog.Call> calls = scan(root).callsByItem().get(caller);
        assertEquals(List.of(AMBIGUOUS, AMBIGUOUS, DYNAMIC, DYNAMIC, RESOLVED),
                calls.stream().map(TalendCatalog.Call::resolution).toList());
        assertTrue(calls.subList(0, 4).stream().allMatch(call -> call.target() == null));
        assertEquals(unique, calls.get(4).target().item());
    }

    @Test
    void resolvesWithinEachRepositoryAndOnlyIndexesJobsInProcessFolders() throws IOException {
        Path left = repository("left");
        Path right = repository("right");
        Path leftTarget = job(left, "process_group/deep/Target_1.0", "fixture-target", "1.0", "fixture-property", "");
        Path rightTarget = job(right, "process/Target_2.0", "fixture-target", "2.0", "fixture-property", "");
        job(left, "notes/Target_3.0", "fixture-target", "3.0", "fixture-property", "");
        job(left, "process/.drafts/Target_4.0", "fixture-target", "4.0", "fixture-property", "");
        Path caller = job(left, "process/Caller_1.0", "fixture-caller", "1.0", "fixture-property",
                call("localLatest", "fixture-target", null, null)
                        + call("remoteExact", "fixture-target", "2.0", null));

        List<TalendCatalog.Repository> catalogs = TalendCatalog.scan(List.of(right, left), () -> {});
        assertEquals(List.of(left, right), catalogs.stream().map(TalendCatalog.Repository::root).toList());
        TalendCatalog.Repository leftCatalog = catalogs.get(0);
        assertEquals(List.of(caller, leftTarget), leftCatalog.entries().stream().map(TalendCatalog.Entry::item).toList());
        assertEquals(List.of(rightTarget), catalogs.get(1).entries().stream().map(TalendCatalog.Entry::item).toList());
        List<TalendCatalog.Call> calls = leftCatalog.callsByItem().get(caller);
        assertEquals(List.of(RESOLVED, UNRESOLVED), calls.stream().map(TalendCatalog.Call::resolution).toList());
        assertEquals(leftTarget, calls.get(0).target().item());
        assertTrue(calls.get(1).target() == null);
    }

    @Test
    void malformedMetadataAndNonSiblingHrefsDoNotHideValidPairs() throws IOException {
        Path root = repository("first");
        Path valid = job(root, "process/valid/Target_1.0", "fixture-valid", "1.0", "fixture-property", "");
        job(root, "process/MalformedXml_1.0", "fixture-malformed", "1.0", "fixture-property", "");
        Files.writeString(root.resolve("process/MalformedXml_1.0.properties"), "<unclosed>",
                java.nio.file.StandardOpenOption.APPEND);
        writeJobWithHref(root, "process/MalformedHref_1.0", "MalformedHref_1.0.item#%broken");
        writeJobWithHref(root, "process/other/Target_1.0", "../valid/Target_1.0.item#/");

        assertEquals(List.of(valid), scan(root).entries().stream().map(TalendCatalog.Entry::item).toList());
    }

    private Path repository(String name) throws IOException {
        Path root = temp.resolve(name);
        write(root.resolve("talend.project"), "<fixture/>");
        return root;
    }

    private static TalendCatalog.Repository scan(Path root) {
        List<TalendCatalog.Repository> catalogs = TalendCatalog.scan(List.of(root), () -> {});
        assertEquals(1, catalogs.size());
        return catalogs.getFirst();
    }

    private static Path job(Path root, String stem, String id, String version, String backlink, String nodes)
            throws IOException {
        Path item = root.resolve(stem + ".item");
        write(item, "<ProcessType>\n" + nodes + "</ProcessType>\n");
        write(root.resolve(stem + ".properties"), sidecar(id, version, backlink, item.getFileName() + "#/"));
        return item;
    }

    private static void writeJobWithHref(Path root, String stem, String href) throws IOException {
        write(root.resolve(stem + ".item"), "<ProcessType/>");
        write(root.resolve(stem + ".properties"), sidecar("fixture-invalid", "1.0", "fixture-property", href));
    }

    private static String sidecar(String id, String version, String backlink, String href) {
        return """
                <xmi:XMI xmlns:xmi="http://www.omg.org/XMI" xmlns:props="http://www.talend.org/properties">
                  <props:Property xmi:id="fixture-property" id="%s" label="Fixture Target" version="%s" item="fixture-item"/>
                  <props:ProcessItem xmi:id="fixture-item" property="%s">
                    <process href="%s"/>
                  </props:ProcessItem>
                </xmi:XMI>
                """.formatted(id, version, backlink, href);
    }

    private static String call(String component, String id, String version, String dynamic) {
        return "<node componentName=\"tRunJob\">\n"
                + parameter("UNIQUE_NAME", component)
                + parameter("PROCESS", "Fixture Target")
                + parameter("PROCESS:PROCESS_TYPE_PROCESS", id)
                + parameter("PROCESS:PROCESS_TYPE_VERSION", version)
                + parameter("USE_DYNAMIC_JOB", dynamic)
                + "</node>\n";
    }

    private static String parameter(String name, String value) {
        return value == null ? "" : "  <elementParameter name=\"%s\" value=\"%s\"/>\n".formatted(name, value);
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
}
