plugins { kotlin("jvm") version "2.3.21" }

kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java/com/drdisagree/colorblendr/utils/samsung/core")
    }
    test {
        kotlin.srcDir("../app/src/test/java/com/drdisagree/colorblendr/utils/samsung")
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { testLogging { events("passed", "skipped", "failed") } }
