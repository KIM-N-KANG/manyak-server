package com.knk.manyak

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.IdClass
import jakarta.persistence.MappedSuperclass
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `entity` 패키지에는 엔티티만 둔다(KNK-2069). enum과 값 타입은 그 모듈의 `dto` 패키지에 둔다.
 *
 * 모든 모듈의 `..entity..` 패키지 최상위 클래스는 `@Entity`, `@Embeddable`, `@MappedSuperclass`, `@IdClass`가 가리키는 키 타입 중
 * 하나이거나 [ALLOWED_FACADES]에 있어야 한다. 중첩 클래스(`Foo$Bar`)는 바깥 클래스를 따르므로 보지 않지만, 중첩 enum은 잡는다.
 * 엔티티 안에 enum을 중첩 선언해 이 규칙을 우회하지 못하게 하기 위해서다. companion object 같은 다른 중첩 클래스는 허용한다.
 * 엔티티 파일에 최상위 함수나 프로퍼티를 두면 Kotlin이 `<파일>Kt` 파사드 클래스를 만든다. 엔티티 자체의 확장 함수만 담은 파사드는
 * 목록에 이유와 함께 올린다. enum이나 값 타입의 확장은 그 타입과 같은 `dto` 파일에 둔다.
 */
class EntityPackageContentsTests {

    @Test
    fun `entity 패키지에는 엔티티만 둔다`() {
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("com.knk.manyak")
        val idClasses = classes.filter { it.isAnnotatedWith(IdClass::class.java) }
            .map { it.getAnnotationOfType(IdClass::class.java).value.java.name }
            .toSet()

        val violations = classes
            .filter { ENTITY_PACKAGE.containsMatchIn(it.packageName) && ('$' !in it.name || it.isEnum) }
            .filterNot { candidate ->
                ENTITY_ANNOTATIONS.any { candidate.isAnnotatedWith(it) } || candidate.name in idClasses || candidate.name in ALLOWED_FACADES
            }
            .map { it.name }
            .sorted()

        assertThat(violations)
            .describedAs("entity 패키지에 엔티티가 아닌 최상위 타입이나 중첩 enum이 있다. enum과 값 타입은 그 모듈의 dto 패키지로 옮기세요.")
            .isEmpty()
    }

    private companion object {
        val ENTITY_PACKAGE = Regex("""\.entity(\.|$)""")
        val ENTITY_ANNOTATIONS = listOf(Entity::class.java, Embeddable::class.java, MappedSuperclass::class.java)

        val ALLOWED_FACADES = setOf(
            // StoryCreationRequest 엔티티의 확장 함수(isOwnedBy, parentCreationLink, hasSameChainOwnerAs)만 담는다.
            "com.knk.manyak.story.entity.StoryCreationRequestKt",
        )
    }
}
