package me.him188.animationgarden.app.ui.subject

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import me.him188.animationgarden.app.ui.framework.AbstractViewModel
import me.him188.animationgarden.datasources.bangumi.BangumiClient
import me.him188.animationgarden.datasources.bangumi.Rating
import me.him188.animationgarden.datasources.bangumi.models.subjects.BangumiSubjectDetails
import me.him188.animationgarden.datasources.bangumi.models.subjects.BangumiSubjectImageSize
import me.him188.animationgarden.datasources.bangumi.models.subjects.BangumiSubjectInfo
import me.him188.animationgarden.datasources.bangumi.models.subjects.BangumiSubjectTag
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@Stable
class SubjectDetailsViewModel(
    subjectId: String,
) : AbstractViewModel(), KoinComponent {
    private val bangumiClient: BangumiClient by inject()
//    private val subjectProvider: SubjectProvider by inject()

    val subjectId: MutableStateFlow<String> = MutableStateFlow(subjectId)

    private val subject: StateFlow<BangumiSubjectDetails?> = this.subjectId.mapLatest {
        bangumiClient.subjects.getSubjectById(it.toLong())
    }.stateInBackground()

    private val subjectNotNull = subject.mapNotNull { it }

    val chineseName: SharedFlow<String> =
        subjectNotNull.map { subject ->
            subject.chineseName.takeIf { it.isNotBlank() } ?: subject.originalName
        }.shareInBackground()
    val officialName: SharedFlow<String> =
        subjectNotNull.map { it.originalName }.shareInBackground()

    val coverImage: SharedFlow<String> = subjectNotNull.map {
        bangumiClient.subjects.getSubjectImageUrl(
            it.id,
            BangumiSubjectImageSize.LARGE
        )
    }.shareInBackground()

    val totalEpisodes: SharedFlow<Int> =
        subjectNotNull.map { it.totalEpisodes }.shareInBackground()

    val tags: SharedFlow<List<BangumiSubjectTag>> =
        subjectNotNull.map { it.tags }
            .map { tags -> tags.sortedByDescending { it.count } }
            .shareInBackground()

    val ratingScore: SharedFlow<String> = subjectNotNull.map { it.rating.score }
        .mapLatest { String.format(".2f", it) }.shareInBackground()
    val ratingCounts: SharedFlow<Map<Rating, Int>> =
        subjectNotNull.map { it.rating.count }.shareInBackground()

    val infoboxList: SharedFlow<List<BangumiSubjectInfo>> =
        subjectNotNull.map { it.infobox }.shareInBackground()
}