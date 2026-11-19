# Talend Job Tools for IntelliJ IDEA

[Русская версия](README_RU.md)

Edit **Talend Studio 8** Jobs in **IntelliJ IDEA 2026.3** (build 263.7307 or later in the 263 series). The plugin edits Talend source files, not generated Java.

## Build and install

Download the ZIP from [GitHub Releases](https://github.com/atrevido/idea-talend-job-tools/releases), or build it from the repository root with JDK 25 and an IDEA 2026.3 installation that includes the Java and XML plugins. On macOS, you can use the runtime shipped with IDEA:

```sh
export IDEA_HOME="$HOME/Applications/IntelliJ IDEA.app"
export JAVA_HOME="$IDEA_HOME/Contents/jbr/Contents/Home"
./gradlew check buildPlugin
```

In IDEA, use **Settings → Plugins → ⚙ → Install Plugin from Disk** and select the downloaded ZIP or `build/distributions/talend-idea-plugin-<version>.zip`.

## Work with a Talend project

1. Open the directory containing `talend.project`, or its immediate parent. Set the IDEA Project SDK to the JDK version your Jobs use.
2. Double-click a Job, Routine, or Repository Context in the **Talend** tool window. For a static `tRunJob` call, the plugin opens the requested version or the latest version if none is specified, provided the target is unambiguous. Use **Refresh** after changes to the project structure.
3. In a Job `.item`, select **Tools → Edit Talend Java…** or the matching editor context-menu action. Fragments open in a standard IDEA editor tab with Java actions such as completion, navigation, and formatting.
4. Select **Apply to Job**, then **Save All**. Closing the last tab without applying discards the draft. The plugin changes only the selected XML attribute and refuses to write if the Job changed meanwhile or contains mixed line endings. Avoid editing the same Job in Studio concurrently: Studio may save its stale copy over your change.

Supported fragments include `tJava`, `tJavaRow`, and existing expressions and filters in standard `tMap`, including empty values. Completion uses Job context fields, row schemas, and Routines from the current Talend repository. External classes need their dependencies configured in IDEA. The `IMPORT` field uses a text editor so IDEA cannot remove imports used in another XML attribute.

Fragments are not standalone Java classes. The plugin does not support refactorings that require modifying generated classes or Job schemas. It does not edit the graph or generate, compile, or run Jobs.

## CI and releases

Pull requests run JUnit tests and build the plugin ZIP against a pinned IDEA SDK. The ZIP is available as a workflow artifact, and test reports are retained if tests fail. Tests use synthetic Jobs and do not require a separate Talend project.

To release, update `version.txt` and merge the change into `main`. The **Release** workflow runs the same checks and publishes GitHub Release `v<version>` with the tested ZIP, `SHA256SUMS`, and `BUILD_INFO.txt`. Pushes that do not change an already published version still run the checks but do not change the release. If a run fails, rerun the original run: a draft can only be resumed from the same commit. Gradle uses `version.txt` for the plugin descriptor and archive version.

### JetBrains Marketplace

Upload the first signed ZIP manually to register plugin `dev.talend.idea`. Before enabling CI publication, create the GitHub environment `marketplace` and restrict deployments to the `main` branch. Set its secrets `MARKETPLACE_TOKEN`, `MARKETPLACE_SIGNING_KEY` (encrypted PEM), `MARKETPLACE_SIGNING_CERT` (PEM), and `MARKETPLACE_SIGNING_KEY_PASSWORD`. Then set the **repository** Actions variable `MARKETPLACE_ENABLED` to `true`.

Each newly published GitHub Release is then signed and submitted to Marketplace Stable using its ZIP, checked against the digest from CI. Submitting does not make the update immediately available; check its status in Marketplace. An existing GitHub release is not submitted again by a later push. If Marketplace did not accept the upload and the job failed, re-run **only** the Marketplace job in the original **Release** run, not the whole workflow.
