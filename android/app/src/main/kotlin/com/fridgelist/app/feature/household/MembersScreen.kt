package com.fridgelist.app.feature.household

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.R
import com.fridgelist.app.platform.Share
import com.fridgelist.app.ui.common.ErrorText
import com.fridgelist.app.ui.common.Formatting
import com.fridgelist.app.ui.common.LanguageRadioList
import com.fridgelist.app.ui.common.PrimaryButton
import com.fridgelist.app.ui.common.QrImage
import com.fridgelist.app.ui.common.SectionLabel
import com.fridgelist.app.ui.common.LanguageOption
import com.fridgelist.core.model.INVITABLE_ROLES
import com.fridgelist.core.model.MemberDoc
import com.fridgelist.core.model.Role
import java.util.Locale

@StringRes
fun Role.label(): Int = when (this) {
    Role.OWNER -> R.string.role_owner
    Role.ADULT -> R.string.role_adult
    Role.KID -> R.string.role_kid
    Role.HELPER -> R.string.role_helper
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(state: MembersUiState, onInvite: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.members_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.canInvite) {
                val label = stringResource(R.string.members_invite)
            ExtendedFloatingActionButton(
                    onClick = onInvite,
                // This Material version does not expose the FAB text to TalkBack; label it explicitly.
                modifier = Modifier.semantics { contentDescription = label },
                    icon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) },
                    text = { Text(stringResource(R.string.members_invite)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.padding(padding)) {
            items(state.members, key = { it.uid }) { member -> MemberRow(member, isMe = member.uid == state.myUid) }
        }
    }
}

@Composable
private fun MemberRow(member: MemberDoc, isMe: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    member.displayName.trim().take(1).ifEmpty { "?" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(member.displayName, style = MaterialTheme.typography.bodyLarge)
            if (isMe) {
                Text(stringResource(R.string.members_you), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // A label, not a chip button: roles change only through the server (CONTRACT §3.4).
        Surface(
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                stringResource(member.role.label()),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun InviteContent(
    state: InviteUiState,
    onRole: (Role) -> Unit,
    onCreate: () -> Unit,
    onShare: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.invite_title), style = MaterialTheme.typography.titleLarge)
        SectionLabel(stringResource(R.string.invite_role_header))
        LanguageRadioList(
            options = INVITABLE_ROLES.map { LanguageOption(it.wire, it.label()) },
            selected = state.role.wire,
            onSelect = { onRole(Role.fromWire(it)) },
        )
        val invite = state.invite
        if (invite == null) {
            state.error?.let { ErrorText(stringResource(it)) }
            PrimaryButton(stringResource(R.string.invite_create), onCreate, busy = state.busy)
        } else {
            val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
            QrImage(
                content = invite.url,
                contentDescription = stringResource(R.string.invite_qr_description),
                modifier = Modifier.size(200.dp).align(Alignment.CenterHorizontally),
            )
            Text(
                invite.code,
                style = MaterialTheme.typography.displaySmall.copy(textDirection = TextDirection.Ltr),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                stringResource(R.string.invite_expires, Formatting.dateTime(invite.expiresAt, locale)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            val shareText = stringResource(R.string.invite_share_text, invite.url, invite.code)
            OutlinedButton(onClick = { onShare(shareText) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = null)
                Text(stringResource(R.string.invite_share), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersRoute(onBack: () -> Unit, vm: MembersViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val invite by vm.invite.collectAsStateWithLifecycle()
    val context = LocalContext.current
    MembersScreen(state, onInvite = vm::openInvite, onBack = onBack)
    invite?.let { inviteState ->
        ModalBottomSheet(onDismissRequest = vm::closeInvite) {
            InviteContent(inviteState, vm::onRoleChange, vm::createInvite, onShare = { Share.text(context, it) })
        }
    }
}
