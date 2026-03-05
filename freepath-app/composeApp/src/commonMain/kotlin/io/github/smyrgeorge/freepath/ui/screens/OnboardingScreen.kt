package io.github.smyrgeorge.freepath.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.smyrgeorge.composeapp.generated.resources.Res
import io.github.smyrgeorge.composeapp.generated.resources.app_name
import io.github.smyrgeorge.composeapp.generated.resources.bio_hint
import io.github.smyrgeorge.composeapp.generated.resources.bio_label
import io.github.smyrgeorge.composeapp.generated.resources.continue_button
import io.github.smyrgeorge.composeapp.generated.resources.finish_button
import io.github.smyrgeorge.composeapp.generated.resources.identity_description
import io.github.smyrgeorge.composeapp.generated.resources.identity_title
import io.github.smyrgeorge.composeapp.generated.resources.location_hint
import io.github.smyrgeorge.composeapp.generated.resources.location_label
import io.github.smyrgeorge.composeapp.generated.resources.name_hint
import io.github.smyrgeorge.composeapp.generated.resources.name_label
import io.github.smyrgeorge.composeapp.generated.resources.node_id_label
import io.github.smyrgeorge.composeapp.generated.resources.profile_title
import io.github.smyrgeorge.composeapp.generated.resources.security_note
import io.github.smyrgeorge.composeapp.generated.resources.tagline
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.ui.components.ButtonVariant
import io.github.smyrgeorge.freepath.ui.components.FreepathButton
import io.github.smyrgeorge.freepath.ui.components.FreepathCard
import io.github.smyrgeorge.freepath.ui.components.FreepathFingerprint
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    onContinue: () -> Unit,
) {
    var showProfile by remember { mutableStateOf(false) }
    val nodeId = AppState.identityEntry.nodeId
    var name by remember { mutableStateOf(defaultName(nodeId)) }
    var bio by remember { mutableStateOf(defaultBio(nodeId)) }
    var location by remember { mutableStateOf(defaultLocation(nodeId)) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo area
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            color = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⬡",
                        fontSize = 32.sp,
                        color = MaterialTheme.colorScheme.background
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(Res.string.tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            if (!showProfile) {
                // Step 1: Identity
                FreepathCard(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.identity_title),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(Res.string.node_id_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            FreepathFingerprint(text = AppState.identityEntry.nodeId)
                            Text(
                                text = stringResource(Res.string.identity_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                FreepathButton(
                    onClick = { showProfile = true },
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Primary,
                ) {
                    Text(
                        text = stringResource(Res.string.continue_button),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            } else {
                // Step 2: Profile
                FreepathCard(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.profile_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = name,
                                onValueChange = { if (it.length <= ContactCard.MAX_NAME_LENGTH) name = it },
                                label = { Text(stringResource(Res.string.name_label)) },
                                placeholder = { Text(stringResource(Res.string.name_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = bio,
                                onValueChange = { if (it.length <= ContactCard.MAX_BIO_LENGTH) bio = it },
                                label = { Text(stringResource(Res.string.bio_label)) },
                                placeholder = { Text(stringResource(Res.string.bio_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 4,
                            )
                            OutlinedTextField(
                                value = location,
                                onValueChange = { if (it.length <= ContactCard.MAX_LOCATION_LENGTH) location = it },
                                label = { Text(stringResource(Res.string.location_label)) },
                                placeholder = { Text(stringResource(Res.string.location_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                FreepathButton(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            AppState.completeOnboarding(name, bio, location)
                            onContinue()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Primary,
                    enabled = !isLoading,
                ) {
                    Text(
                        text = stringResource(Res.string.finish_button),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(Res.string.security_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun nodeIdHash(nodeId: String): Int =
    nodeId.fold(0L) { acc, c -> acc * 31 + c.code }.toInt()

private data class DefaultProfile(val name: String, val bio: String, val location: String)

private val defaultProfiles = listOf(
    DefaultProfile("Agile Axolotl", "Moves through shadows unseen", "Ashenvale"),
    DefaultProfile("Brave Basilisk", "Faces the unknown boldly", "Brimswick"),
    DefaultProfile("Cosmic Capybara", "Drifts between stars peacefully", "Crystalmere"),
    DefaultProfile("Daring Dodo", "Leaps before looking always", "Dawnshard"),
    DefaultProfile("Elegant Echidna", "Precise in all things", "Emberglow"),
    DefaultProfile("Fierce Flamingo", "Stands tall, burns bright", "Frostspire"),
    DefaultProfile("Gallant Gazelle", "Swift across open plains", "Goldmere"),
    DefaultProfile("Heroic Heron", "Patient, then strikes fast", "Highcliff"),
    DefaultProfile("Intrepid Ibis", "Wanders far from home", "Ironveil"),
    DefaultProfile("Jolly Jaguar", "Laughs loud in the dark", "Jadewood"),
    DefaultProfile("Kind Kestrel", "Watchful from the heights", "Kaldera"),
    DefaultProfile("Lively Lynx", "Quick, quiet, always curious", "Lumindra"),
    DefaultProfile("Mystic Marmot", "Knows things left unsaid", "Mirewood"),
    DefaultProfile("Noble Narwhal", "Pierces the deepest waters", "Northveil"),
    DefaultProfile("Orbital Ocelot", "Always circling, never settling", "Oakhaven"),
    DefaultProfile("Playful Pangolin", "Rolls with every tide", "Pinecrest"),
    DefaultProfile("Quiet Quokka", "Smiles through every storm", "Quarrystone"),
    DefaultProfile("Radiant Raccoon", "Finds gold in rubble", "Ravenfield"),
    DefaultProfile("Swift Salamander", "Gone before you blink", "Silverdale"),
    DefaultProfile("Trusty Tapir", "Steady ground beneath you", "Thornbury"),
    DefaultProfile("Unique Uakari", "Unlike anything seen before", "Umbrafell"),
    DefaultProfile("Vivid Vicuña", "Leaves color in passing", "Verdania"),
    DefaultProfile("Wily Walrus", "Smarter than I look", "Westmarch"),
    DefaultProfile("Xenial Xenops", "Welcomes all who wander", "Xandria"),
    DefaultProfile("Youthful Yak", "Forever young at heart", "Yellowmoor"),
    DefaultProfile("Zealous Zorilla", "Burns with quiet purpose", "Zephyr Coast"),
    DefaultProfile("Amber Alpaca", "Warm wool, warmer heart", "Azureton"),
    DefaultProfile("Bold Binturong", "Holds ground, asks questions", "Crestfall"),
    DefaultProfile("Clever Condor", "Sees everything from above", "Duskholm"),
    DefaultProfile("Dreamy Dugong", "Floats through quiet waters", "Emberton"),
    DefaultProfile("Eerie Eland", "Haunts the twilight meadows", "Farwatch"),
    DefaultProfile("Fabled Fennec", "Hears what others miss", "Greywood"),
    DefaultProfile("Graceful Grebe", "Glides without leaving ripples", "Hollowfen"),
    DefaultProfile("Humble Hoopoe", "Digs deep, stays grounded", "Ironhurst"),
    DefaultProfile("Ivory Impala", "Leaps over every obstacle", "Jadewater"),
)

private fun profileIndex(nodeId: String): Int {
    val hash = nodeIdHash(nodeId)
    return ((hash % defaultProfiles.size) + defaultProfiles.size) % defaultProfiles.size
}

private fun defaultName(nodeId: String): String = defaultProfiles[profileIndex(nodeId)].name
private fun defaultBio(nodeId: String): String = defaultProfiles[profileIndex(nodeId)].bio
private fun defaultLocation(nodeId: String): String = defaultProfiles[profileIndex(nodeId)].location


