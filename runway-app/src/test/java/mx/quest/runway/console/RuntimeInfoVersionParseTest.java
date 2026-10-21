package mx.quest.runway.console;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link RuntimeInfoService#versionFromJarName} must parse a version out of a Spring Boot <b>nested</b> jar URL
 * (terminated by {@code "!/"}), which is what a class's code-source location looks like in the packaged jar. This
 * guards the fix for the bug where the trailing {@code "!/"} produced an empty file name, silently dropping every
 * jar without an {@code Implementation-Version} (BouncyCastle, Prometheus) from the About page's component list —
 * a defect invisible in exploded test runs (where the location is a plain {@code file:} path).
 */
class RuntimeInfoVersionParseTest {

    @Test
    void parsesNestedBootJarUrls() {
        // new (Boot 3.2+) nested scheme
        assertThat(RuntimeInfoService.versionFromJarName(
                "jar:nested:/opt/runway-app.jar/!BOOT-INF/lib/bcprov-jdk18on-1.80.jar!/")).isEqualTo("1.80");
        // classic nested scheme, and a dotted qualifier version
        assertThat(RuntimeInfoService.versionFromJarName(
                "jar:file:/opt/runway-app.jar!/BOOT-INF/lib/attoparser-2.0.7.RELEASE.jar!/"))
                .isEqualTo("2.0.7.RELEASE");
        assertThat(RuntimeInfoService.versionFromJarName(
                "jar:nested:/opt/app.jar/!BOOT-INF/lib/prometheus-metrics-core-1.4.3.jar!/")).isEqualTo("1.4.3");
    }

    @Test
    void parsesPlainFileJarUrls() {
        assertThat(RuntimeInfoService.versionFromJarName(
                "file:/home/user/.m2/repository/org/bouncycastle/bcprov-jdk18on/1.80/bcprov-jdk18on-1.80.jar"))
                .isEqualTo("1.80");
    }

    @Test
    void returnsNullForNoVersionOrExplodedDir() {
        assertThat(RuntimeInfoService.versionFromJarName(
                "file:/home/user/runway/runway-app/target/classes/")).isNull(); // exploded class dir
        assertThat(RuntimeInfoService.versionFromJarName("file:/opt/lib/somelib.jar")).isNull(); // no -version
        assertThat(RuntimeInfoService.versionFromJarName(null)).isNull();
    }
}
