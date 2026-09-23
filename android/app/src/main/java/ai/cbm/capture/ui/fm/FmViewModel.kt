package ai.cbm.capture.ui.fm

import ai.cbm.capture.data.session.SessionStore
import ai.cbm.capture.data.work.WorkRepository
import ai.cbm.capture.data.work.WorkResult
import ai.cbm.capture.domain.model.FmAction
import ai.cbm.capture.domain.model.FmCard
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
 * The facility manager's queue and their decisions.
 *
 * A decision is recorded through the workflows' own guarded action: this app never moves a ticket
 * itself. Authorizing takes effect at once; approving a completion is recorded and WF2 carries it
 * out within about a minute, which is what the screen then says.
 */
@HiltViewModel
class FmViewModel @Inject constructor(
    private val work: WorkRepository,
    private val store: SessionStore
) : ViewModel() {

    private val _state = MutableStateFlow(
        FmUiState(
            siteName = store.current()?.membership?.siteName.orEmpty(),
            siteCode = store.current()?.membership?.siteId.orEmpty(),
            expiresAtMillis = store.current()?.expiresAt?.toEpochMilli()
        )
    )
    val state: StateFlow<FmUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            when (val result = work.fmQueue()) {
                is WorkResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        authorizations = result.value.authorizations,
                        completions = result.value.completions,
                        counts = result.value.counts,
                        error = null
                    )
                }
                is WorkResult.Failed -> _state.update { it.copy(loading = false, error = result.message) }
                is WorkResult.Stale -> _state.update { it.copy(loading = false, error = result.message) }
                WorkResult.LoggedOut -> _state.update { it.copy(loading = false) }
            }
        }
    }

    /** One tap. [reason] is required for a rejection or a rework request; the database enforces it too. */
    fun decide(card: FmCard, action: FmAction, reason: String?) {
        viewModelScope.launch {
            _state.update { it.copy(busyTicket = card.ticketId, notice = null, error = null) }
            when (val result = work.decide(card, action, reason)) {
                is WorkResult.Ok -> {
                    val r = result.value
                    _state.update {
                        it.copy(
                            busyTicket = null,
                            notice = when (action) {
                                FmAction.AUTHORIZE -> "Authorized. The job is being offered to technicians now."
                                FmAction.REJECT -> "Rejected. The person who reported it is told."
                                FmAction.APPROVE -> "Approved. The ticket is closing — give it a minute."
                                FmAction.REWORK -> "Sent back to the technician, with your reason."
                            }
                        )
                    }
                    refresh()
                }
                is WorkResult.Stale -> {
                    // Decided by email or in the chat meanwhile: show where the ticket stands now.
                    _state.update { it.copy(busyTicket = null, notice = result.message) }
                    refresh()
                }
                is WorkResult.Failed -> _state.update { it.copy(busyTicket = null, error = result.message) }
                WorkResult.LoggedOut -> _state.update { it.copy(busyTicket = null) }
            }
        }
    }
}
