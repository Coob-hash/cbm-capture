package ai.cbm.capture.ui.technician

import ai.cbm.capture.data.session.SessionStore
import ai.cbm.capture.data.work.WorkRepository
import ai.cbm.capture.data.work.WorkResult
import ai.cbm.capture.domain.model.JobCard
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The technician's offers, jobs and trades.
 *
 * Answering an offer writes exactly what the offer email's link writes, so the two can never
 * disagree: the first valid answer wins and the second is refused.
 */
@HiltViewModel
class TechnicianViewModel @Inject constructor(
    private val work: WorkRepository,
    private val store: SessionStore
) : ViewModel() {

    private val _state = MutableStateFlow(
        TechUiState(
            siteCode = store.current()?.membership?.siteId.orEmpty(),
            expiresAtMillis = store.current()?.expiresAt?.toEpochMilli()
        )
    )
    val state: StateFlow<TechUiState> = _state.asStateFlow()


    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            when (val result = work.technicianJobs()) {
                is WorkResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        me = result.value.me,
                        catalog = result.value.skillCatalog,
                        offers = result.value.offers,
                        current = result.value.current,
                        completed = result.value.completed,
                        error = null
                    )
                }
                is WorkResult.Failed -> _state.update { it.copy(loading = false, error = result.message) }
                is WorkResult.Stale -> _state.update { it.copy(loading = false, error = result.message) }
                WorkResult.LoggedOut -> _state.update { it.copy(loading = false) }
            }
        }
    }

    fun answerOffer(card: JobCard, accept: Boolean) {
        val offerId = card.offer?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(busyTicket = card.ticketId, notice = null, error = null) }
            when (val result = work.answerOffer(card.ticketId, offerId, accept)) {
                is WorkResult.Ok -> {
                    _state.update {
                        it.copy(
                            busyTicket = null,
                            notice = if (accept) "Accepted. It appears under To report in a moment."
                            else "Declined. The job goes to another technician."
                        )
                    }
                    refresh()
                }
                is WorkResult.Stale -> {
                    _state.update { it.copy(busyTicket = null, notice = "That offer is no longer open.") }
                    refresh()
                }
                is WorkResult.Failed -> _state.update { it.copy(busyTicket = null, error = result.message) }
                WorkResult.LoggedOut -> _state.update { it.copy(busyTicket = null) }
            }
        }
    }

    /** Open the trades picker on the trades already set (Q17: theirs to change, and only theirs). */
    fun editSkills() = _state.update { it.copy(editingSkills = true, notice = null, error = null) }

    fun cancelEditSkills() = _state.update { it.copy(editingSkills = false, error = null) }

    fun saveSkills(skills: List<String>) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = work.setSkills(skills)) {
                is WorkResult.Ok -> {
                    _state.update {
                        it.copy(editingSkills = false, notice = "Saved. You will be offered jobs in these trades.")
                    }
                    refresh()
                }
                is WorkResult.Failed -> _state.update { it.copy(loading = false, error = result.message) }
                is WorkResult.Stale -> _state.update { it.copy(loading = false, error = result.message) }
                WorkResult.LoggedOut -> _state.update { it.copy(loading = false) }
            }
        }
    }

}
