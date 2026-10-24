package dev.jvmmcp.core.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

@AnalyzeClasses(packages = "dev.jvmmcp.core", importOptions = ImportOption.DoNotIncludeTests.class)
public class CoreArchitectureTest {

    @ArchTest
    static final ArchRule coreMustHaveZeroExternalFrameworkDependencies = noClasses()
        .that().resideInAPackage("dev.jvmmcp.core..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "info.picocli..",
            "com.fasterxml.jackson..",
            "com.google.gson..",
            "org.apache.commons.."
        )
        .because("The core module must remain lightweight and isolated from large external frameworks");

    @ArchTest
    static final ArchRule domainModelsMustNotDependOnInfrastructureOrServices = noClasses()
        .that().resideInAPackage("dev.jvmmcp.core.model..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "dev.jvmmcp.core.jmx..",
            "dev.jvmmcp.core.attach..",
            "dev.jvmmcp.core.spring.."
        )
        .because("Domain model records must remain pure DTOs without coupling to reader/service infrastructure");

    @ArchTest
    static final ArchRule coreMustNotWriteToStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
        .because("The MCP server speaks JSON-RPC over stdio, so core must log through System.Logger instead of writing to System.out or System.err");
}
