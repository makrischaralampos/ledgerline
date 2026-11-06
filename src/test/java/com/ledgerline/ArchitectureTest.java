package com.ledgerline;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.ledgerline.common.DomainException;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

/** Executable architecture documentation: these rules fail the build if the design erodes. */
@AnalyzeClasses(
    packagesOf = LedgerlineApplication.class,
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    @ArchTest
    static final ArchRule featurePackagesHaveNoCycles = slices()
        .matching("com.ledgerline.(*)..")
        .should()
        .beFreeOfCycles();

    @ArchTest
    static final ArchRule controllersNeverTouchRepositories = noClasses()
        .that()
        .haveSimpleNameEndingWith("Controller")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule servicesNeverDependOnControllers = noClasses()
        .that()
        .haveSimpleNameEndingWith("Service")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule servicesAndRepositoriesAreWebFrameworkFree =
        noClasses()
            .that()
            .haveSimpleNameEndingWith("Service")
            .or()
            .haveSimpleNameEndingWith("Repository")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.web..");

    @ArchTest
    static final ArchRule businessExceptionsExtendDomainException = classes()
        .that()
        .haveSimpleNameEndingWith("Exception")
        .and()
        .doNotHaveSimpleName("DomainException")
        .should()
        .beAssignableTo(DomainException.class);

    @ArchTest
    static final ArchRule noFieldInjection =
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noStandardStreams =
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
}
