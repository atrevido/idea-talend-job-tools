package dev.talend.idea;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class TalendCatalog {
    private static final String XMI = "http://www.omg.org/XMI";
    private static final String PROPERTIES_NS = "http://www.talend.org/properties";
    private static final String SIDECAR_EXTENSION = ".properties";
    private static final String LATEST_VERSION = "Latest";

    enum Kind {
        JOB("process"), TEST("process"), ROUTINE("content"), CONTEXT("context");

        private final String linkTag;

        Kind(String linkTag) {
            this.linkTag = linkTag;
        }

        boolean matchesItem(XMLStreamReader xml) {
            String tag = xml.getLocalName();
            return switch (this) {
                case JOB -> tag.endsWith("ProcessItem");
                case TEST -> tag.equals("TestContainerItem");
                case ROUTINE -> tag.equals("RoutineItem") && PROPERTIES_NS.equals(xml.getNamespaceURI());
                case CONTEXT -> tag.equals("ContextItem") && PROPERTIES_NS.equals(xml.getNamespaceURI());
            };
        }

        boolean hasCalls() {
            return this == JOB || this == TEST;
        }
    }

    enum Resolution { RESOLVED, UNRESOLVED, AMBIGUOUS, DYNAMIC }

    record Entry(Kind kind, Path item, String id, String label, String version) {}
    record Call(String component, String process, String version, int line, Resolution resolution, Entry target) {}
    record Repository(Path root, List<Entry> entries, Map<Path, List<Call>> callsByItem) {}

    private record JobKey(String id, String version) {}

    private TalendCatalog() {}

    static List<Repository> scan(Collection<Path> roots, Runnable checkCanceled) {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External XML resources are disabled");
        });
        List<Repository> repositories = new ArrayList<>();
        for (Path root : roots) {
            checkCanceled.run();
            if (Files.isDirectory(root, NOFOLLOW_LINKS)
                    && Files.isRegularFile(root.resolve("talend.project"), NOFOLLOW_LINKS)) {
                repositories.add(scanRepository(root, factory, checkCanceled));
            }
        }
        repositories.sort(Comparator.comparing(Repository::root));
        return List.copyOf(repositories);
    }

    private static Repository scanRepository(Path root, XMLInputFactory factory, Runnable checkCanceled) {
        List<Entry> entries = new ArrayList<>();
        scanProcesses(root, Kind.JOB, factory, entries, checkCanceled);
        scanProcesses(root.resolve("tests"), Kind.TEST, factory, entries, checkCanceled);
        if (Files.isDirectory(root.resolve("code"), NOFOLLOW_LINKS)) {
            scanDirectory(root.resolve("code/routines"), Kind.ROUTINE, factory, entries, checkCanceled);
        }
        scanDirectory(root.resolve("context"), Kind.CONTEXT, factory, entries, checkCanceled);
        entries.sort(Comparator.comparing(Entry::item));

        JobTargets targets = new JobTargets();
        for (Entry entry : entries) {
            if (entry.kind() == Kind.JOB) targets.add(entry);
        }
        Map<Path, List<Call>> callsByItem = new HashMap<>();
        for (Entry entry : entries) {
            checkCanceled.run();
            if (!entry.kind().hasCalls()) continue;
            try {
                List<Call> calls = readXml(factory, entry.item(), xml -> readCalls(xml, targets, checkCanceled));
                if (!calls.isEmpty()) callsByItem.put(entry.item(), List.copyOf(calls));
            } catch (IOException | XMLStreamException ignored) {
                // Retain the repository entry even if the job itself cannot be read.
            }
        }
        return new Repository(root, List.copyOf(entries), Map.copyOf(callsByItem));
    }

    private static void scanProcesses(Path directory, Kind kind, XMLInputFactory factory,
                                      List<Entry> entries, Runnable checkCanceled) {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                checkCanceled.run();
                String name = child.getFileName().toString();
                if (name.equals("process") || name.startsWith("process_")) {
                    scanDirectory(child, kind, factory, entries, checkCanceled);
                }
            }
        } catch (IOException | DirectoryIteratorException ignored) {
            // The remaining repository groups can still be read.
        }
    }

    private static void scanDirectory(Path directory, Kind kind, XMLInputFactory factory,
                                      List<Entry> entries, Runnable checkCanceled) {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                    checkCanceled.run();
                    return path.getFileName().toString().startsWith(".")
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    checkCanceled.run();
                    if (attributes.isRegularFile() && path.getFileName().toString().endsWith(SIDECAR_EXTENSION)) {
                        readEntry(path, kind, factory, entries, checkCanceled);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException exception) {
                    checkCanceled.run();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path path, IOException exception) {
                    checkCanceled.run();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // A directory removed during refresh need not hide other groups.
        }
    }

    private static void readEntry(Path sidecar, Kind kind, XMLInputFactory factory,
                                  List<Entry> entries, Runnable checkCanceled) {
        String filename = sidecar.getFileName().toString();
        String itemName = filename.substring(0, filename.length() - SIDECAR_EXTENSION.length()) + ".item";
        Path itemPath = sidecar.resolveSibling(itemName);
        if (!Files.isRegularFile(itemPath, NOFOLLOW_LINKS)) return;
        try {
            Entry entry = readXml(factory, sidecar, xml -> readSidecar(xml, kind, itemPath, itemName, checkCanceled));
            if (entry != null) entries.add(entry);
        } catch (IOException | XMLStreamException ignored) {
            // Malformed metadata must not prevent the catalog from opening; do not log its contents.
        }
    }

    private static Entry readSidecar(XMLStreamReader xml, Kind kind, Path itemPath, String itemName,
                                     Runnable checkCanceled) throws XMLStreamException {
        int depth = 0;
        Entry entry = null;
        String propertyXmiId = null;
        String itemKey = null;
        String linkedPropertyKey = null;
        String linkedItemKey = null;
        boolean inItem = false;
        boolean hasHref = false;
        while (xml.hasNext()) {
            switch (next(xml, checkCanceled)) {
                case XMLStreamConstants.START_ELEMENT -> {
                    depth++;
                    String tag = xml.getLocalName();
                    if (depth == 1 && !(tag.equals("XMI") && XMI.equals(xml.getNamespaceURI()))) return null;
                    if (depth == 2 && tag.equals("Property") && PROPERTIES_NS.equals(xml.getNamespaceURI())) {
                        if (entry != null) return null;
                        propertyXmiId = xml.getAttributeValue(XMI, "id");
                        itemKey = attribute(xml, "item");
                        String id = attribute(xml, "id");
                        String label = attribute(xml, "label");
                        String version = attribute(xml, "version");
                        if (id.isBlank() || label.isBlank() || version.isBlank()) return null;
                        entry = new Entry(kind, itemPath, id, label, version);
                    } else if (depth == 2 && kind.matchesItem(xml)) {
                        if (linkedItemKey != null) return null;
                        linkedItemKey = xml.getAttributeValue(XMI, "id");
                        linkedPropertyKey = attribute(xml, "property");
                        if (linkedItemKey == null || linkedItemKey.isBlank()) return null;
                        inItem = true;
                    } else if (depth == 3 && inItem && tag.equals(kind.linkTag)) {
                        if (!pointsToSibling(attribute(xml, "href"), itemName)) return null;
                        hasHref = true;
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    if (depth == 2) inItem = false;
                    depth--;
                }
                default -> { }
            }
        }
        return hasHref && entry != null && linkedItemKey.equals(itemKey) && !linkedPropertyKey.isBlank()
                && (linkedPropertyKey.equals(entry.id()) || linkedPropertyKey.equals(propertyXmiId)) ? entry : null;
    }

    private static boolean pointsToSibling(String href, String itemName) {
        try {
            URI uri = new URI(href);
            return !uri.isAbsolute() && uri.getRawAuthority() == null && uri.getRawQuery() == null
                    && itemName.equals(uri.getPath());
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private static List<Call> readCalls(XMLStreamReader xml, JobTargets targets, Runnable checkCanceled)
            throws XMLStreamException {
        List<Call> calls = new ArrayList<>();
        int depth = 0;
        RunJob node = null;
        while (xml.hasNext()) {
            switch (next(xml, checkCanceled)) {
                case XMLStreamConstants.START_ELEMENT -> {
                    depth++;
                    String tag = xml.getLocalName();
                    if (depth == 1 && !(tag.equals("ProcessType") || tag.equals("TestContainer"))) return List.of();
                    if (depth == 2 && tag.equals("node") && attribute(xml, "componentName").equals("tRunJob")) {
                        node = new RunJob(Math.max(0, xml.getLocation().getLineNumber() - 1));
                    } else if (depth == 3 && node != null && tag.equals("elementParameter")) {
                        node.readParameter(xml);
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    if (depth == 2 && node != null) {
                        calls.add(node.resolve(targets));
                        node = null;
                    }
                    depth--;
                }
                default -> { }
            }
        }
        return calls;
    }

    private static final class JobTargets {
        private final Map<JobKey, Entry> exact = new HashMap<>();
        private final Map<String, LatestJob> latest = new HashMap<>();

        private void add(Entry entry) {
            if (!isNumericVersion(entry.version())) return;
            JobKey key = new JobKey(entry.id(), entry.version());
            // A present key with a null value marks a duplicate, not an absent job.
            exact.put(key, exact.containsKey(key) ? null : entry);

            LatestJob current = latest.get(entry.id());
            if (current == null) {
                latest.put(entry.id(), new LatestJob(entry));
                return;
            }
            int order = compareVersions(entry.version(), current.entry.version());
            if (order > 0) {
                current.entry = entry;
                current.ambiguous = false;
            } else if (order == 0) {
                current.ambiguous = true;
            }
        }
    }

    private static final class LatestJob {
        private Entry entry;
        private boolean ambiguous;

        private LatestJob(Entry entry) {
            this.entry = entry;
        }
    }

    private static boolean isNumericVersion(String version) {
        boolean hasDigit = false;
        for (int i = 0; i < version.length(); i++) {
            char ch = version.charAt(i);
            if (ch >= '0' && ch <= '9') hasDigit = true;
            else if (ch == '.' && hasDigit) hasDigit = false;
            else return false;
        }
        return hasDigit;
    }

    private static int compareVersions(String left, String right) {
        int leftStart = 0;
        int rightStart = 0;
        while (leftStart < left.length() && rightStart < right.length()) {
            int leftEnd = left.indexOf('.', leftStart);
            int rightEnd = right.indexOf('.', rightStart);
            if (leftEnd < 0) leftEnd = left.length();
            if (rightEnd < 0) rightEnd = right.length();

            // Compare significant digits without converting segments to fixed-width integers.
            while (leftStart < leftEnd && left.charAt(leftStart) == '0') leftStart++;
            while (rightStart < rightEnd && right.charAt(rightStart) == '0') rightStart++;
            int order = Integer.compare(leftEnd - leftStart, rightEnd - rightStart);
            if (order != 0) return order;
            while (leftStart < leftEnd) {
                order = Character.compare(left.charAt(leftStart++), right.charAt(rightStart++));
                if (order != 0) return order;
            }

            leftStart = leftEnd + 1;
            rightStart = rightEnd + 1;
        }
        return Boolean.compare(leftStart < left.length(), rightStart < right.length());
    }

    private static final class RunJob {
        private final int line;
        private String component = "tRunJob";
        private String process = "";
        private String id;
        private String version;
        private String dynamic;
        private boolean duplicateParameter;

        private RunJob(int line) {
            this.line = line;
        }

        private void readParameter(XMLStreamReader xml) {
            switch (attribute(xml, "name")) {
                case "UNIQUE_NAME" -> component = attribute(xml, "value");
                case "PROCESS" -> process = attribute(xml, "value");
                case "PROCESS:PROCESS_TYPE_PROCESS" -> {
                    duplicateParameter |= id != null;
                    id = attribute(xml, "value");
                }
                case "PROCESS:PROCESS_TYPE_VERSION" -> {
                    duplicateParameter |= version != null;
                    version = attribute(xml, "value");
                }
                case "USE_DYNAMIC_JOB" -> {
                    duplicateParameter |= dynamic != null;
                    dynamic = attribute(xml, "value");
                }
                default -> { }
            }
        }

        private Call resolve(JobTargets targets) {
            String requestedVersion = version == null ? "" : version;
            Resolution resolution = Resolution.UNRESOLVED;
            Entry target = null;
            if (duplicateParameter) {
                resolution = Resolution.AMBIGUOUS;
            } else if (dynamic != null && !dynamic.equals("false")) {
                resolution = Resolution.DYNAMIC;
            } else if (id != null && !id.isBlank()) {
                if (version == null || version.equals(LATEST_VERSION)) {
                    LatestJob latest = targets.latest.get(id);
                    if (latest != null) {
                        if (latest.ambiguous) {
                            resolution = Resolution.AMBIGUOUS;
                        } else {
                            target = latest.entry;
                            resolution = Resolution.RESOLVED;
                        }
                    }
                } else {
                    JobKey key = new JobKey(id, version);
                    target = targets.exact.get(key);
                    if (target != null) resolution = Resolution.RESOLVED;
                    else if (targets.exact.containsKey(key)) resolution = Resolution.AMBIGUOUS;
                }
            }
            return new Call(component, process, requestedVersion, line, resolution, target);
        }
    }

    private static int next(XMLStreamReader xml, Runnable checkCanceled) throws XMLStreamException {
        checkCanceled.run();
        int event = xml.next();
        if (event == XMLStreamConstants.DTD) throw new XMLStreamException("DOCTYPE is disabled");
        return event;
    }

    private static String attribute(XMLStreamReader xml, String name) {
        String value = xml.getAttributeValue(XMLConstants.NULL_NS_URI, name);
        return value == null ? "" : value;
    }

    private static <T> T readXml(XMLInputFactory factory, Path path, XmlReader<T> reader)
            throws IOException, XMLStreamException {
        try (InputStream stream = Files.newInputStream(path, NOFOLLOW_LINKS)) {
            XMLStreamReader xml = factory.createXMLStreamReader(stream);
            try {
                return reader.read(xml);
            } finally {
                xml.close();
            }
        }
    }

    @FunctionalInterface
    private interface XmlReader<T> {
        T read(XMLStreamReader xml) throws XMLStreamException;
    }
}
