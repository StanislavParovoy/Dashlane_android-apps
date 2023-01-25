package com.dashlane.ui.screens.fragments.userdata.sharing.items

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dashlane.core.DataSync
import com.dashlane.events.AppEvents
import com.dashlane.events.SyncFinishedEvent
import com.dashlane.navigation.Navigator
import com.dashlane.server.api.endpoints.sharinguserdevice.ItemGroup
import com.dashlane.server.api.endpoints.sharinguserdevice.Permission
import com.dashlane.session.Session
import com.dashlane.session.SessionManager
import com.dashlane.ui.screens.fragments.userdata.sharing.SharingModels
import com.dashlane.ui.screens.fragments.userdata.sharing.center.SharingDataProvider
import com.dashlane.useractivity.log.usage.UsageLogCode134
import com.dashlane.vault.util.desktopId
import com.dashlane.xml.domain.SyncObjectType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SharingItemsForUserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionManager: SessionManager,
    private val sharingItemsDataProvider: SharingItemsDataProvider,
    private val sharingDataProvider: SharingDataProvider,
    appEvents: AppEvents,
    private val navigator: Navigator
) : ViewModel(), SharingItemsForUserViewModelContract {
    private val session: Session?
        get() = sessionManager.session

    override val memberLogin =
        SharingItemsForUserFragmentArgs.fromSavedStateHandle(savedStateHandle)
            .argsMemberLogin

    override val uiState: MutableStateFlow<SharingItemsForUserViewModelContract.UIState> =
        MutableStateFlow(SharingItemsForUserViewModelContract.UIState.Loading)

    private val dataList = mutableListOf<SharingModels.ItemUser>()

    init {
        reloadData()
        appEvents.register(this, SyncFinishedEvent::class.java, false) {
            reloadData()
        }
    }

    override fun reloadData() {
        val login = session?.username?.email ?: return
        viewModelScope.launch {
            runCatching {
                sharingItemsDataProvider.getItemsForUser(login, memberLogin)
            }.onSuccess {
                dataList.apply {
                    clear()
                    addAll(it)
                    if (isEmpty()) {
                        navigator.popBackStack()
                    } else {
                        uiState.tryEmit(SharingItemsForUserViewModelContract.UIState.Data(it))
                    }
                }
            }.onFailure {
                navigator.popBackStack()
            }
        }
    }

    override fun onItemClicked(itemId: String, dataType: SyncObjectType) =
        navigator.goToItem(itemId, dataType.desktopId)

    override fun pullToRefresh() = DataSync.sync(UsageLogCode134.Origin.MANUAL)

    override fun onResendInvite(itemGroup: ItemGroup) {
        uiState.tryEmit(SharingItemsForUserViewModelContract.UIState.RequestLoading)
        viewModelScope.launch {
            runCatching {
                sharingDataProvider.resendInvite(itemGroup, memberLogin)
            }.onFailure {
                uiState.tryEmit(SharingItemsForUserViewModelContract.UIState.RequestFailure)
            }.onSuccess {
                uiState.tryEmit(SharingItemsForUserViewModelContract.UIState.RequestSuccess)
                reloadData()
            }
        }
    }

    override fun onCancelInvite(itemGroupId: String) = onRevokeUser(itemGroupId)

    override fun onRevokeUser(itemGroupId: String) {
        val itemGroup = dataList.find { it.itemGroup.groupId == itemGroupId }?.itemGroup ?: return
        uiState.tryEmit(SharingItemsForUserViewModelContract.UIState.RequestLoading)
        viewModelScope.launch {
            runCatching {
                sharingDataProvider.cancelInvitation(
                    itemGroup,
                    listOf(memberLogin)
                )
            }.onFailure {
                uiState.tryEmit(SharingItemsForUserViewModelContract.UIState.RequestFailure)
            }.onSuccess {
                uiState.tryEmit(SharingItemsForUserViewModelContract.UIState.RequestSuccess)
                reloadData()
            }
        }
    }

    override fun onChangePermission(itemGroup: ItemGroup, newPermission: Permission) {
        uiState.tryEmit(SharingItemsForUserViewModelContract.UIState.RequestLoading)
        viewModelScope.launch {
            runCatching {
                sharingDataProvider.updateItemGroupMember(
                    itemGroup,
                    newPermission,
                    memberLogin
                )
            }.onFailure {
                uiState.tryEmit(SharingItemsForUserViewModelContract.UIState.RequestFailure)
            }.onSuccess {
                uiState.tryEmit(SharingItemsForUserViewModelContract.UIState.RequestSuccess)
                reloadData()
            }
        }
    }
}