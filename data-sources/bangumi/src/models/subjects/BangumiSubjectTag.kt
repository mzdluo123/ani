package me.him188.animationgarden.datasources.bangumi.models.subjects

import kotlinx.serialization.Serializable
import me.him188.animationgarden.datasources.api.Subject
import me.him188.animationgarden.datasources.api.SubjectImages
import me.him188.animationgarden.datasources.bangumi.BangumiClientImpl

@Serializable
data class BangumiSubjectTag(
    val name: String,
    val count: Int,
)

fun BangumiSubject.toSubject(): Subject {
    val subject = this
    return Subject(
        id = subject.id.toString(),
        originalName = subject.name,
        chineseName = subject.nameCN,
        images = SubjectImages(
            landscapeCommon = BangumiClientImpl.getSubjectImageUrl(
                subject.id,
                BangumiSubjectImageSize.MEDIUM
            ),
            largePoster = BangumiClientImpl.getSubjectImageUrl(
                subject.id,
                BangumiSubjectImageSize.LARGE
            ),
        ),
        score = subject.score,
        rank = subject.rank,
        sourceUrl = "https://bangumi.tv/subject/${subject.id}",
        tags = subject.tags.map { it.name to it.count },
        summary = subject.summary,
    )
}
