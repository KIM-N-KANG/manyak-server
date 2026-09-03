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
    // OTLP 메트릭 자동구성(OtlpMetricsExportAutoConfiguration.otlpConfig)이 이 모듈의 OpenTelemetryProperties 빈을
    // 인자로 받는다. 빼면 OTLP 레지스트리가 아예 뜨지 않는다(레지스트리 자동구성 조건 미충족).
    implementation("org.springframework.boot:spring-boot-opentelemetry")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    implementation("io.sentry:sentry-spring-boot-4:8.43.1")
    // 메트릭은 OTLP push로 Grafana Cloud에 보낸다(스펙 §4-7). prometheus 레지스트리는 로컬에서
    // /actuator/prometheus로 export 내용을 눈으로 확인하는 용도이며 운영에서는 export를 끈다.
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // 컴파일이 생성한 인물 이미지를 S3에 올린다(KNK-966). 스프링 BOM이 관리하지 않아 버전을 명시한다.
    implementation("software.amazon.awssdk:s3:2.46.7")
    // FCM 푸시 발송(KNK-1130). HTTP v1 인증(서비스 계정 OAuth 토큰 발급·갱신)을 SDK에 맡긴다.
    implementation("com.google.firebase:firebase-admin:9.10.0") {
        // FCM만 쓴다. Firestore·Storage 클라이언트는 gRPC·netty·gax를 통째로 끌고 와 jar를 수십 MB 불리므로 뺀다.
        // FirebaseApp 초기화는 이 둘을 참조하지 않는다(FcmConfigTest가 초기화 경로를 고정한다).
        exclude(group = "com.google.cloud", module = "google-cloud-firestore")
        exclude(group = "com.google.cloud", module = "google-cloud-storage")
    }
    // firebase-admin 초기화가 JacksonFactory를 참조하는데, 그 아티팩트는 위에서 뺀 Storage를 통해서만 들어왔다. 직접 든다.
    implementation("com.google.http-client:google-http-client-jackson2:2.1.0")
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
        // -Xemit-jvm-type-annotations: 컬렉션 원소 제약(`List<@NotBlank @Size(max = 30) String>`)을 살린다(KNK-862).
        // 이 플래그가 없으면 코틀린이 타입 애노테이션을 클래스 파일에 내보내지 않아
        // (`javap -v`에 RuntimeVisibleTypeAnnotations 없음) Hibernate Validator가 원소 제약을 보지 못하고,
        // 검증을 통과한 과길이 값이 저장 단계에서 터져 400이 아니라 500이 된다. 지우면 그 간극이 되살아난다.
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
            "-Xemit-jvm-type-annotations",
        )
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 통합 테스트는 Spring 컨텍스트를 30개 넘게 캐시에 상주시킨다(각각 앱·Tomcat·H2·커넥션풀).
    // Gradle 기본 힙(512m)으로는 GC에 시간을 뺏겨 CI에서 테스트 클라이언트가 ReadTimeout으로 끊긴다
    // (OOM이 아니라 지연이다 — KNK-686). 러너는 7~16GB이므로 여유를 준다.
    // maxParallelForks는 올리지 않는다: 포크마다 컨텍스트 캐시와 임베디드 Redis가 복제돼 메모리가 배로 든다.
    maxHeapSize = "2g"
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
    // 테스트 HTTP 클라이언트는 keep-alive 커넥션을 재사용하지 않는다(KNK-823).
    // RestTestClient는 RestClient 기본 팩토리를 쓰는데, webflux 스타터 때문에 reactor-netty가 감지돼
    // ReactorClientHttpRequestFactory가 잡히고, 그 커넥션 풀은 JVM 전역(HttpResources)이며 기본값이
    // "maxIdleTime 없음"이라 유휴 커넥션을 영원히 보관한다. 서버가 그 사이 커넥션을 닫으면(Tomcat
    // keep-alive 만료, 컨텍스트 캐시 축출로 Tomcat 종료 후 포트 재사용) 풀은 죽은 커넥션을 그대로
    // 내주고, FIN을 아직 처리하지 못한 찰나에 요청이 실리면 POST가 재시도 없이 터진다
    // (PrematureCloseException: "Connection has been closed BEFORE response, while sending request body").
    // 전체 스위트에서만·간헐로 나던 SSE 통합 테스트 실패의 정체가 이것이다.
    // 0이면 축출 조건이 idleTime >= 0 이라 풀에 들어간 커넥션은 항상 축출된다 = 재사용 자체가 없어져
    // 확률을 낮추는 게 아니라 경합 자체가 사라진다. 로컬호스트 핸드셰이크 비용은 무시할 수준이다.
    systemProperty("reactor.netty.pool.maxIdleTime", "0")
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}
