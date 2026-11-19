# Talend Job Tools for IntelliJ IDEA

[Русская версия](README_RU.md)

Edit **Talend Studio 8** Jobs in **IntelliJ IDEA 2026.3** (build 263.7307 or later in the 263 series). The plugin edits Talend source files, not generated Java.

Browse Jobs, Routines, Repository Contexts, and static `tRunJob` links. Use IDEA's Java navigation and completion across Routines and in supported Job fragments. To resolve Talend runtime classes and other Routine dependencies, [import the Routine classpath](#import-routine-dependencies-from-studio) from Studio's local Maven cache.

## Build and install

Download the ZIP from [GitHub Releases](https://github.com/atrevido/idea-talend-job-tools/releases), or build it from the repository root with JDK 25 and an IDEA 2026.3 installation that includes the Java and XML plugins. On macOS, you can use the runtime shipped with IDEA:

```sh
export IDEA_HOME="$HOME/Applications/IntelliJ IDEA.app"
export JAVA_HOME="$IDEA_HOME/Contents/jbr/Contents/Home"
./gradlew check buildPlugin
```

In IDEA, use **Settings → Plugins → ⚙ → Install Plugin from Disk** to install or update the plugin. Select the downloaded ZIP or `build/distributions/talend-idea-plugin-<version>.zip` and restart IDEA if prompted.

## Work with a Talend project

1. Open the directory containing `talend.project`, or its immediate parent. Set the IDEA Project SDK to the JDK version your Jobs use.
2. Double-click a Job, Routine, or Repository Context in the **Talend** tool window. For a static `tRunJob` call, the plugin opens the requested version or the latest version if none is specified, provided the target is unambiguous. Use **Refresh** after changes to the project structure.
3. In a Job `.item`, select **Tools → Edit Talend Java…** or the matching editor context-menu action. Fragments open in a standard IDEA editor tab with Java actions such as completion, navigation, and formatting.
4. Select **Apply to Job**, then **Save All**. Closing the last tab without applying discards the draft. The plugin changes only the selected XML attribute and refuses to write if the Job changed meanwhile or contains mixed line endings. Avoid editing the same Job in Studio concurrently: Studio may save its stale copy over your change.

On project open, IDEA indexes the original Java Routine `.item` files under `code/routines`. Talend folders are organizational; Java package declarations determine how Routines resolve. Wait for indexing to finish before using completion, Go to Declaration, or Find Usages. Find Usages on a Routine also includes semantic matches in supported Java fragments in unopened Jobs; those entries navigate to the Job attribute and are not rename targets.

Supported fragments include `tJava`, `tJavaRow`, and existing expressions and filters in standard `tMap`, including empty values. Completion uses Job context fields, row schemas, and Routines from the current Talend repository. External classes need their dependencies configured in IDEA. The `IMPORT` field uses a text editor so IDEA cannot remove imports used in another XML attribute.

Fragments are not standalone Java classes. The plugin does not support refactorings that require modifying generated classes or Job schemas. It does not edit the graph or generate, compile, or run Jobs.

### Import Routine dependencies from Studio

The project JDK and the Studio libraries provide different parts of the Java classpath. For example, `java.util.HexFormat` comes from JDK 17, while Log4j and the Talend system routines come from the Maven dependencies declared for the Routines.

1. In Talend Studio, generate the Maven POMs and make sure Studio's local Maven cache contains the project's Routine dependencies. The import reads `poms/code/routines/pom.xml` and, when declared as its parent, `poms/pom.xml`.
2. In IDEA, check that the module containing `code/routines` uses the Java version required by the generated POM. If the module inherits its SDK, check the Project SDK. The SDK selected in the import dialog is only used to run Maven; it does not change either IDEA setting.
3. In a trusted IDEA project, open **Tools → Import Talend Routine Classpath…**. Select a trusted Apache Maven executable and Studio's local Maven repository. For the default Talend Studio 8 installation on macOS, the repository is `/Applications/TalendStudio-8/studio/configuration/.m2/repository`.
4. After the import reports the number of JARs, wait for IDEA to finish indexing. Open a Routine `.item` and use **Go to Declaration** (`⌘B` on macOS) on an imported class such as `LogManager`, if the project depends on Log4j.

The import runs Maven Dependency Plugin 3.9.0 **offline** and attaches the resolved compile classpath to the IDEA module that owns the Routine sources. If Maven reports a missing artifact or plugin, populate the selected cache in Studio or through your normal Maven setup, then retry. Rerun the import after changing Routine dependencies.

The import does not attach every JAR from the cache, download artifacts, or build Jobs. Dependencies specific to individual Jobs and Java variables generated by Studio are outside its scope.

## CI and releases

Run `./gradlew check buildPlugin` before proposing a manual IDE check. Agree on the scenario and IDEA instance with the user before testing in their IDE.

Pull requests run JUnit tests and build the plugin ZIP against a pinned IDEA SDK. The ZIP is available as a workflow artifact, and test reports are retained if tests fail. Tests use synthetic Jobs and do not require a separate Talend project.

To release, update `version.txt` and merge the change into `main`. The **Release** workflow runs the same checks and publishes GitHub Release `v<version>` with the tested ZIP, `SHA256SUMS`, and `BUILD_INFO.txt`. Pushes that do not change an already published version still run the checks but do not change the release. If a run fails, rerun the original run: a draft can only be resumed from the same commit. Gradle uses `version.txt` for the plugin descriptor and archive version.
