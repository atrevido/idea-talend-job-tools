package dev.talend.idea;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class TalendMavenClasspath {
    private static final String POM_NAMESPACE = "http://maven.apache.org/POM/4.0.0";
    private static final Path MASTER = Path.of("poms", "pom.xml");
    private static final Path ROUTINES = Path.of("poms", "code", "routines", "pom.xml");
    private static final String GOAL = "org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath";
    private static final String JAVA_ARTIFACT_TYPES = "jar,test-jar,maven-plugin,ejb,ejb-client";
    private static final List<String> COORDINATES = List.of("groupId", "artifactId", "version");
    private static final Set<String> MODEL_FIELDS = Set.of("properties", "dependencies", "dependencyManagement");
    // The fixed goal must not pick up project properties that select, rename, or suppress its output.
    private static final Set<String> GOAL_PROPERTIES = Set.of(
            "classifier", "type", "includeArtifactIds", "includeClassifiers", "includeGroupIds", "includeScope", "includeTypes",
            "excludeArtifactIds", "excludeClassifiers", "excludeGroupIds", "excludeScope", "excludeTransitive", "excludeTypes",
            "markersDirectory", "outputEncoding", "silent", "overWriteIfNewer", "overWriteReleases", "overWriteSnapshots");

    private TalendMavenClasspath() {}

    static List<Path> resolve(Path talendRoot, Path mavenHomeOrExecutable, Path localRepository, Path javaHome)
            throws IOException, InterruptedException {
        return resolve(talendRoot, mavenHomeOrExecutable, localRepository, javaHome, () -> {});
    }

    static List<Path> resolve(Path talendRoot, Path mavenHomeOrExecutable, Path localRepository, Path javaHome,
                              Runnable checkCanceled) throws IOException, InterruptedException {
        checkCanceled.run();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Path root = talendRoot.toRealPath();
        Path repo = localRepository.toRealPath();
        Path jdk = javaHome.toRealPath();
        boolean windows = File.separatorChar == '\\';
        if (!Files.isDirectory(repo)) throw new IOException("Select a directory for the local Maven repository.");
        if (repo.toString().indexOf(File.pathSeparatorChar) >= 0) {
            throw new IOException("The local Maven repository path contains the classpath separator.");
        }
        if (!Files.isExecutable(jdk.resolve(windows ? "bin/java.exe" : "bin/java"))) {
            throw new IOException("Select a Java home containing a Java executable.");
        }
        if (!mavenHomeOrExecutable.isAbsolute()) throw new IOException("Select an absolute Maven executable path.");
        Path maven = Files.isDirectory(mavenHomeOrExecutable)
                ? mavenHomeOrExecutable.resolve(windows ? "bin/mvn.exe" : "bin/mvn") : mavenHomeOrExecutable;
        maven = maven.toRealPath();
        if (!Files.isRegularFile(maven) || !Files.isExecutable(maven)) {
            throw new IOException("Select an executable from a trusted Maven installation.");
        }
        String launcher = maven.getFileName().toString().toLowerCase(Locale.ROOT);
        if (launcher.equals("mvnw") || launcher.startsWith("mvnw.") || launcher.equals("mvnd") || launcher.startsWith("mvnd.")) {
            throw new IOException("Select an Apache Maven CLI executable, not Maven Wrapper or Maven Daemon.");
        }
        if (windows && !launcher.endsWith(".exe")) {
            throw new IOException("This resolver requires a native Maven executable on Windows; batch launchers are not supported.");
        }

        try (Scratch scratch = new Scratch()) {
            Path work = scratch.directory;
            copyModels(root, work, checkCanceled);
            // Maven's launcher discovers .mvn by walking upwards from -f. Stop it in our own directory hierarchy.
            Files.createDirectory(work.resolve(".mvn"));
            Path settings = Files.writeString(work.resolve("settings.xml"), "<settings/>\n");
            Path toolchains = Files.writeString(work.resolve("toolchains.xml"), "<toolchains/>\n");
            Path output = work.resolve("classpath.txt");
            Path log = work.resolve("maven.log");
            ProcessBuilder builder = new ProcessBuilder(maven.toString(), "-o", "-B", "-ntp", "-N",
                    "-s", settings.toString(), "-gs", settings.toString(),
                    "-t", toolchains.toString(), "-gt", toolchains.toString(), "-f", work.resolve(ROUTINES).toString(),
                    "-Dmaven.repo.local=" + repo, GOAL, "-DincludeScope=compile", "-DincludeTypes=" + JAVA_ARTIFACT_TYPES, "-DoutputEncoding=UTF-8",
                    "-Dmdep.outputFile=" + output);
            builder.directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            // Do not inherit JVM/launcher options, credentials, shell startup files, or executable search paths.
            var environment = builder.environment();
            String systemRoot = environment.get("SystemRoot");
            environment.clear();
            if (windows && systemRoot != null) {
                environment.put("SystemRoot", systemRoot);
                environment.put("PATH", Path.of(systemRoot, "System32").toString());
            } else {
                environment.put("PATH", "/usr/bin:/bin");
            }
            environment.put("JAVA_HOME", jdk.toString());
            environment.put("HOME", work.toString());
            environment.put("MAVEN_SKIP_RC", "true");
            environment.put("MAVEN_BASEDIR", work.toString());
            String isolatedHome = launcherPath(work);
            environment.put("JDK_JAVA_OPTIONS", "-Duser.home=" + isolatedHome + " -Djava.io.tmpdir=" + isolatedHome + " -Dfile.encoding=UTF-8");
            checkCanceled.run();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Process process = builder.start();
            try {
                process.getOutputStream().close();
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) checkCanceled.run();
                checkCanceled.run();
                if (process.exitValue() != 0) throw mavenFailure(process.exitValue(), log);
                return readClasspath(output, repo, checkCanceled);
            } finally {
                if (process.isAlive()) stop(process);
            }
        }
    }

    private static String launcherPath(Path path) throws IOException {
        String text = path.toString();
        if (text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IOException("The temporary Maven working path cannot be passed safely to Java.");
        }
        return '"' + text + '"';
    }

    private static void copyModels(Path root, Path work, Runnable checkCanceled) throws IOException {
        DocumentBuilder builder;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", 64);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // Never print raw parser diagnostics.
        } catch (ParserConfigurationException e) {
            throw new IOException("Could not configure safe Talend POM parsing.", e);
        }

        Element routine = loadModel(builder, root, ROUTINES);
        Element parent = child(routine, "parent");
        Element master = null;
        if (parent != null) {
            Path parentPath;
            try {
                String relative = text(parent, "relativePath", "../pom.xml");
                parentPath = Path.of(relative);
                if (relative.isEmpty() || parentPath.isAbsolute()) throw unsupportedParent();
                parentPath = ROUTINES.getParent().resolve(parentPath).normalize();
                if (parentPath.equals(MASTER.getParent())) parentPath = parentPath.resolve("pom.xml");
            } catch (InvalidPathException e) {
                throw unsupportedParent();
            }
            if (!parentPath.equals(MASTER)) throw unsupportedParent();
            master = loadModel(builder, root, MASTER);
            if (child(master, "parent") != null) throw unsupportedParent();
            for (String coordinate : COORDINATES) {
                String expected = text(master, coordinate, "");
                if (expected.isEmpty() || !expected.equals(text(parent, coordinate, ""))) throw unsupportedParent();
            }
        }
        checkCanceled.run();
        if (master != null) writeModel(builder.newDocument(), master, work.resolve(MASTER), true, null);
        writeModel(builder.newDocument(), routine, work.resolve(ROUTINES), false, parent);
    }

    private static IOException unsupportedParent() {
        return new IOException("Only the generated poms/pom.xml can be the Routine POM parent; further parents are unsupported.");
    }

    private static Element loadModel(DocumentBuilder builder, Path root, Path relative) throws IOException {
        Path file = root.resolve(relative);
        if (!Files.isRegularFile(file) || !file.equals(file.toRealPath())) {
            throw new IOException("Missing or non-local generated Talend Maven POM: " + relative);
        }
        try (var input = Files.newInputStream(file)) {
            Element project = builder.parse(input).getDocumentElement();
            if (!"project".equals(project.getLocalName()) || !mavenElement(project)) throw new SAXException();
            return project;
        } catch (SAXException e) {
            throw new IOException("Invalid or unsafe XML in the generated Talend Maven POM: " + relative);
        }
    }

    private static void writeModel(Document output, Element source, Path file, boolean master, Element parent) throws IOException {
        if (!"4.0.0".equals(text(source, "modelVersion", ""))) throw new IOException("Expected a Maven 4.0.0 POM model.");
        String packaging = master ? "pom" : "jar";
        if (!packaging.equals(text(source, "packaging", "jar"))) {
            throw new IOException("The generated Talend Maven POM uses unsupported packaging.");
        }
        Element project = output.createElementNS(POM_NAMESPACE, "project");
        output.appendChild(project);
        append(project, "modelVersion", "4.0.0");
        if (parent != null) {
            Element parentCopy = append(project, "parent", null);
            copyCoordinates(parentCopy, parent);
            append(parentCopy, "relativePath", "../../pom.xml");
        }
        copyCoordinates(project, source);
        append(project, "packaging", packaging);
        copyDependencyModel(project, source);
        Element profiles = child(source, "profiles");
        if (profiles != null) {
            Element targetProfiles = append(project, "profiles", null);
            for (Element profile : children(profiles)) {
                if (!"profile".equals(profile.getLocalName())) continue;
                Element targetProfile = append(targetProfiles, "profile", null);
                append(targetProfile, "id", text(profile, "id", "default"));
                Element activation = child(profile, "activation");
                if (activation != null) {
                    if (child(activation, "file") != null) {
                        throw new IOException("File-activated Maven profiles cannot be reproduced in the offline Routine import.");
                    }
                    copyValueTree(targetProfile, activation);
                }
                copyDependencyModel(targetProfile, profile);
            }
        }
        Files.createDirectories(file.getParent());
        try (var stream = Files.newOutputStream(file)) {
            TransformerFactory factory = TransformerFactory.newDefaultInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            factory.newTransformer().transform(new DOMSource(output), new StreamResult(stream));
        } catch (TransformerException e) {
            throw new IOException("Could not write the isolated Routine Maven model.", e);
        }
    }

    private static void copyCoordinates(Element target, Element source) {
        for (String coordinate : COORDINATES) {
            String value = text(source, coordinate, null);
            if (value != null) append(target, coordinate, value);
        }
    }

    private static void copyDependencyModel(Element target, Element source) throws IOException {
        for (Element field : children(source)) {
            String name = field.getLocalName();
            if (MODEL_FIELDS.contains(name)) {
                if (name.equals("properties")) {
                    for (Element property : children(field)) {
                        String key = property.getLocalName();
                        if (key.startsWith("mdep.") || GOAL_PROPERTIES.contains(key)) {
                            throw new IOException("The generated Talend POM sets properties reserved by the classpath goal.");
                        }
                    }
                }
                copyValueTree(target, field);
            } else if (name.equals("repositories") || name.equals("pluginRepositories")) {
                copyRepositories(target, field);
            }
        }
    }

    private static void copyRepositories(Element target, Element source) throws IOException {
        String name = source.getLocalName();
        String entry = name.equals("repositories") ? "repository" : "pluginRepository";
        Element repositories = append(target, name, null);
        for (Element repository : children(source)) {
            if (!entry.equals(repository.getLocalName())) continue;
            Element copy = append(repositories, entry, null);
            append(copy, "id", text(repository, "id", ""));
            // Resolver tracks download origins by ID. Keep the IDs and policies, not checkout URLs or credentials.
            append(copy, "url", "https://offline.invalid/");
            for (String key : List.of("layout", "releases", "snapshots")) {
                Element value = child(repository, key);
                if (value != null) copyValueTree(copy, value);
            }
        }
    }

    private static void copyValueTree(Element target, Element source) throws IOException {
        String name = source.getLocalName();
        if (name.equals("systemPath")) {
            throw new IOException("System-path Maven dependencies are not supported by the offline Routine import.");
        }
        List<Element> nested = children(source);
        if (nested.isEmpty()) {
            append(target, name, source.getTextContent());
        } else {
            Element copy = append(target, name, null);
            for (Element child : nested) copyValueTree(copy, child);
        }
    }

    private static Element append(Element target, String name, String value) {
        Element child = target.getOwnerDocument().createElementNS(POM_NAMESPACE, name);
        if (value != null) child.setTextContent(value);
        target.appendChild(child);
        return child;
    }

    private static String text(Element parent, String name, String fallback) {
        Element value = child(parent, name);
        return value == null ? fallback : value.getTextContent().strip();
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName()) && mavenElement(element)) return element;
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && mavenElement(element)) result.add(element);
        }
        return result;
    }

    private static boolean mavenElement(Element element) {
        String namespace = element.getNamespaceURI();
        return namespace == null || POM_NAMESPACE.equals(namespace);
    }

    private static List<Path> readClasspath(Path output, Path repo, Runnable checkCanceled) throws IOException {
        if (!Files.isRegularFile(output)) throw new IOException("Maven did not write the Routine classpath file.");
        String classpath = Files.readString(output);
        if (classpath.endsWith("\r\n")) {
            classpath = classpath.substring(0, classpath.length() - 2);
        } else if (classpath.endsWith("\n")) {
            classpath = classpath.substring(0, classpath.length() - 1);
        }
        if (classpath.isEmpty()) return List.of();
        Set<Path> result = new LinkedHashSet<>();
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator), -1)) {
            checkCanceled.run();
            Path path;
            try {
                path = Path.of(entry);
            } catch (InvalidPathException e) {
                throw new IOException("Maven returned an invalid file path in the Routine classpath.");
            }
            if (!path.isAbsolute() || !path.normalize().startsWith(repo)) {
                throw new IOException("Maven returned a classpath entry outside the selected local repository.");
            }
            try {
                path = path.toRealPath();
            } catch (IOException e) {
                throw new IOException("Maven returned an unavailable file in the Routine classpath.");
            }
            if (!isJarInRepository(path, repo)) throw new IOException("Maven returned an invalid JAR or an artifact outside the selected local repository.");
            result.add(path);
        }
        return List.copyOf(result);
    }

    static boolean isJarInRepository(Path realJar, Path realRepository) {
        return realJar.startsWith(realRepository) && Files.isRegularFile(realJar)
                && realJar.getFileName().toString().endsWith(".jar");
    }

    private static IOException mavenFailure(int exit, Path log) {
        String output;
        try (var input = Files.newInputStream(log)) {
            output = new String(input.readNBytes(128 * 1024), StandardCharsets.UTF_8);
        } catch (IOException e) {
            output = "";
        }
        // Use classifications, never raw output: Maven can echo interpolated project properties and paths.
        String detail;
        if (output.contains("PluginResolutionException") || output.contains("Plugin org.apache.maven.plugins:maven-dependency-plugin")) {
            detail = "The dependency plugin 3.9.0 or one of its dependencies is unavailable in the selected cache.";
        } else if (output.contains("Cannot access") && output.contains("offline mode")) {
            detail = "A required artifact is missing from the selected cache or cached under a different repository ID.";
        } else if (output.contains("Non-resolvable parent") || output.contains("Non-resolvable import POM") || output.contains("dependencies.dependency.")) {
            detail = "Maven could not build the dependency model from the generated POMs and the selected cache.";
        } else if (output.contains("Could not resolve dependencies") || output.contains("DependencyResolutionException")) {
            detail = "Maven could not resolve the Routine compile dependencies from the selected cache.";
        } else {
            detail = "Check the selected Maven installation, Java home, and local repository.";
        }
        return new IOException("Offline Maven failed (exit " + exit + "). " + detail);
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        // Finish cleanup even if interruption caused it, then restore the interrupt status.
        boolean interrupted = false;
        for (;;) {
            try {
                process.waitFor();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class Scratch implements AutoCloseable {
        private final Path directory = Files.createTempDirectory("talend-routine-classpath-");

        private Scratch() throws IOException {}

        @Override
        public void close() throws IOException {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
