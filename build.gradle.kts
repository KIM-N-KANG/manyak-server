plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.2.21"
}

group = "com.knk"
version = "0.0.1-SNAPSHOT"
description = "manyak-server"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    implementation("io.sentry:sentry-spring-boot-4:8.43.1")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.github.codemonstur:embedded-redis:1.4.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // LauncherSessionListener(EmbeddedRedisLauncherSessionListener)를 컴파일·구현하려면 launcher가 컴파일 클래스패스에 필요하다.
    testImplementation("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 테스트는 항상 격리된 H2(test 프로파일, Flyway off)로만 돈다. 아래 두 안전장치는
    // @ActiveProfiles("test")를 빠뜨린 통합 테스트가 기본 프로파일(application.yml의 local)로 부팅해
    // datasource가 ${MANYAK_DB_URL}(dev/local Postgres)로 해석되고 Flyway가 실 DB에 도는 격리 유출을 막는다.
    // (개발자 셸에 MANYAK_DB_*가 export돼 있으면 `./gradlew test` 한 번이 실 DB에 마이그레이션을 적용할 수 있다.)
    // 1) 안전 기본값: 프로파일 미지정 시 test로 강제 → H2 + Flyway off.
    systemProperty("spring.profiles.active", "test")
    // 2) 이중 안전장치: 상속된 실 DB 접속정보를 테스트 JVM에서 비운다. test 외 프로파일로 새더라도
    //    실 DB 대신 빈 URL로 즉시 실패하게 만들어 실 DB를 절대 건드리지 않는다.
    environment("MANYAK_DB_URL", "")
    environment("MANYAK_DB_USERNAME", "")
    environment("MANYAK_DB_PASSWORD", "")
    // 3) Spring Boot 표준 env도 차단: SPRING_DATASOURCE_*·SPRING_FLYWAY_*는 relaxed binding으로
    //    application-test.yml의 H2·flyway off 설정보다 우선하므로, export돼 있으면 test 프로파일이어도
    //    테스트가 외부 DB로 붙거나 Flyway가 되살아난다.
    //    ""로 비우면 빈 값도 유효한 override라 test yml 설정을 지우므로 remove로 아예 제거한다.
    environment.keys.filter { it.startsWith("SPRING_DATASOURCE_") || it.startsWith("SPRING_FLYWAY_") }
        .forEach { environment.remove(it) }
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}
