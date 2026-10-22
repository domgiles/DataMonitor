# Data Monitor - Workspace Instructions

## Project Overview
Java 17+ project using **Ant** and **Ivy** for build automation and dependency management. Focus on clean architecture, modular design, and Java 17+ language features.

## Code Style
- **Java Version**: Source and target set to Java 17 minimum; leverage records, sealed classes, and pattern matching
- **Encoding**: UTF-8 throughout all source files
- **Package Structure**: Reverse domain naming (e.g., `com.datamonitor.module`); separate public API from `.internal.*` implementations
- **Formatting**: Eclipse/IDEA formatter configuration recommended; maintain consistent indentation (4 spaces)
- **Deprecation Warnings**: Enabled during compilation; address deprecations promptly

## Architecture
- **Directory Layout**:
  - `src/main/java/` — Application source code organized by logical modules
  - `src/main/resources/` — Configuration files, properties, XML schemas
  - `src/test/java/` — Unit and integration test code (mirror main structure)
  - `src/test/resources/` — Test fixtures, test data
  - `lib/` — Managed by Ivy; do not commit to VCS
  - `build/` — Generated files (`classes/`, `test-classes/`, `dist/`); ignored by Git
  - `docs/` — Generated JavaDoc

- **Modularity**: Organize code into logical modules within packages; use `module-info.java` for Project Jigsaw module definitions if multi-module structure emerges
- **Dependencies**: All managed through `ivy.xml`; transitive conflicts resolved via `<exclude>` declarations

## Build and Test

### Build Files
- **build.xml** — Main Ant build script; define properties at top, organize targets sequentially
- **ivy.xml** — Dependency declarations with configurations: `compile`, `runtime`, `test`, `build-tools`
- **ivy-settings.xml** — Repository configuration; typically local filesystem cache plus Maven Central

### Essential Commands
```bash
ant clean           # Remove generated files (build/, dist/)
ant compile         # Compile source code (compiles main + dependencies)
ant compile.test    # Compile test source code
ant test            # Run all unit tests; halts on failure
ant jar             # Package compiled code into JAR
ant dist            # Create distribution package
ant docs            # Generate JavaDoc documentation
ant all             # Full build: clean → compile → test → jar
```

### Build Targets Reference
- **Task Dependencies**: Always declare dependencies via `depends="target1,target2"`
- **Classpath Management**: Use `<path>` elements for main, test, and runtime classpaths
- **Ivy Integration**: Call `ivy:resolve` and `ivy:retrieve` early in build chain; define cache directory in `ivy-settings.xml`
- **Java Compilation**: Set `source="17"`, `target="17"`, `release="17"` in `javac` task; enable `fork="true"` for isolation

### Testing
- **Framework**: JUnit (version pinned in `ivy.xml`); use JUnit 5 (`junitlauncher`) for modern features
- **Test Discovery**: Place tests in `src/test/java/` matching package structure of code being tested
- **Execution**: `ant test` runs all tests via classpath filtering; generate XML reports for CI/CD
- **Test Output**: Reports written to `tests/` directory

## Project Conventions

### Build Configuration
- **Property Files**: Use `build.properties` for environment-specific overrides (e.g., custom paths, Java home)
- **Version Management**: Define project version as property in `build.xml`; referenced in JAR manifests and distributions
- **Classpath Idiom**: Never hardcode paths; use `<path>` and `<pathelement>` with property references

### Dependency Management
- **Explicit Versioning**: Pin all dependency versions in `ivy.xml`; avoid `latest.release`
- **Configurations**: Separate compile, runtime, and test dependencies; test deps never leak to distribution
- **Excludes**: Document why transitive excludes are necessary (e.g., `<exclude module="legacy-api"/>`)
- **Local Dependencies**: Small JARs can go in `lib/` and referenced via `<fileset>`, but managed dependencies must use Ivy

### Code Organization
- **Logical Modules**: Group related classes into packages (e.g., `com.datamonitor.service.*`, `com.datamonitor.model.*`)
- **Exception Handling**: Custom exceptions in `*.exception` package; hierarchy extends from checked/unchecked appropriately for Java 17+
- **Utility Classes**: Place static utilities in `*.util.*`; avoid utility class bloat (prefer static factory methods in domain classes)
- **Module-info.java**: When applicable, declare module exports and requires; use strong encapsulation

### Build Script Patterns
```xml
<!-- Define properties at top -->
<property name="project.name" value="datamonitor"/>
<property name="src.dir" value="src/main/java"/>
<property name="build.dir" value="build"/>

<!-- Use path references -->
<path id="main.classpath">
  <fileset dir="lib/compile" includes="*.jar"/>
</path>

<!-- Organize targets with proper dependencies -->
<target name="compile" depends="init">
  <ivy:resolve/>
  <ivy:retrieve pattern="lib/[conf]/[artifact]-[revision].[ext]"/>
  <javac srcdir="${src.dir}" destdir="${build.dir}/classes"
         source="17" target="17" release="17" fork="true"/>
</target>
```

## Integration Points

### Dependencies
- **Ivy Repository**: Configured in `ivy-settings.xml` (typically Maven Central + optional private repos)
- **Transitive Dependency Conflicts**: Managed in `ivy.xml` via `<exclude>` elements
- **Build Tools**: Any Ant extensions (e.g., testing libraries, code analysis) declared in `build-tools` configuration

### Outputs
- **JAR Distribution**: `build/dist/${project.name}.jar` (contains `META-INF/MANIFEST.MF` with version)
- **JavaDoc**: Generated to `docs/` directory; include package-level `package-info.java` files
- **Test Reports**: XML reports in `tests/` for CI/CD integration

## Security
- **No Credentials in VCS**: Use `build.properties` (Git-ignored) for sensitive paths, credentials, or repository URLs
- **Transitive Excludes**: Review and exclude vulnerable or unnecessary transitive dependencies
- **Java Version Baseline**: Java 17+ provides security improvements over earlier versions; always target `release="17"` minimum

---

## Quick Reference for AI Agents
1. Always run `ant clean` before full builds to ensure correctness
2. Check `ivy.xml` before assuming dependencies are available
3. Modify `build.xml` for build logic; use `build.properties` for configuration
4. Use property references for all paths (never hardcode)
5. Run `ant test` after code changes to validate
6. Generated artifacts: JARs → `build/dist/`, distributions → `dist/`, reports → `tests/`
7. Call `ivy:retrieve` before compilation tasks to resolve dependencies
8. Always specify `source="17"` and `target="17"` for Java compilation
9. Keep `build/` and `lib/` out of VCS (covered by `.gitignore`)
10. Document non-obvious build targets with inline comments
