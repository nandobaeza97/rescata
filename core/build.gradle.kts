// Motor de escaneo en Java puro, sin dependencias de Android: se prueba en el PC con `./gradlew :core:test`.
plugins {
    `java-library`
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(8) // garantiza que solo se usan APIs disponibles en Android antiguo
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-options")
}

tasks.named<JavaCompile>("compileTestJava") {
    options.release.set(11)
    options.encoding = "UTF-8"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    systemProperty("java.awt.headless", "true")
}
