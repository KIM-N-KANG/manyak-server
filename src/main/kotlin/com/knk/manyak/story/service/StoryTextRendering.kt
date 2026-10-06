package com.knk.manyak.story.service

import com.knk.manyak.story.dto.*

private fun String.named(name: String?) = UsernameTokenRenderer.render(this, name)

fun StoryStartSettingResponse.rendered(name: String?): StoryStartSettingResponse = copy(
    name = this.name.named(name), prologue = prologue?.named(name), startSituation = startSituation?.named(name),
    suggestedInputs = suggestedInputs.map { it.named(name) }, endings = endings.map { it.rendered(name) },
)
fun StoryEndingResponse.rendered(name: String?): StoryEndingResponse = copy(
    name = this.name.named(name), epilogue = epilogue.named(name),
    requirement = requirement.copy(achievementCondition = requirement.achievementCondition.named(name)),
)
fun StoryMainEventResponse.rendered(name: String?): StoryMainEventResponse = copy(
    name = this.name.named(name), description = description.named(name), keySentence = keySentence.named(name),
)
fun StorySummaryResponse.rendered(name: String?): StorySummaryResponse = copy(title = title.named(name), oneLineIntro = oneLineIntro.named(name))
fun StoryDetailResponse.rendered(name: String?): StoryDetailResponse = copy(
    title = title.named(name), oneLineIntro = oneLineIntro.named(name), description = description?.named(name),
    startSettings = startSettings.map { it.rendered(name) }, mainEvents = mainEvents.map { it.rendered(name) },
    characters = characters.map { it.copy(description = it.description?.named(name)) },
    reachedEndings = reachedEndings.map { it.named(name) },
)
fun SimpleStoryCreateResponse.rendered(name: String?): SimpleStoryCreateResponse = copy(
    title = title.named(name), oneLineIntro = oneLineIntro?.named(name), description = description?.named(name),
    startSettings = startSettings.map { it.rendered(name) },
)
