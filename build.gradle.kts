plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register<Delete>("clean") {
    description = "Deletes the root build directory."
    delete(rootProject.layout.buildDirectory)
}
