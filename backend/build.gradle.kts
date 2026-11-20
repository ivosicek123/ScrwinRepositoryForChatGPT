plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

val ktorVersion = "3.6.1"

kotlin { jvmToolchain(17) }

application { mainClass.set("cz.faltyn.elfall.backend.bankid.AppKt") }

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("com.nimbusds:nimbus-jose-jwt:10.3")
    implementation("com.google.crypto.tink:tink:1.16.0") // Ed25519Signer/Verifier v nimbus
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("ch.qos.logback:logback-classic:1.6.5")
    implementation("com.webauthn4j:webauthn4j-core:0.31.11.RELEASE")
    testImplementation(kotlin("test-junit"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("com.webauthn4j:webauthn4j-test:0.31.11.RELEASE")
}
