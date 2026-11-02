@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ghostlock.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar as MaterialTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlock.app.BuildConfig
import com.ghostlock.app.R
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val scrollBehavior = GhostlockScrollBehavior(rememberTopAppBarState())
    val listState = rememberLazyListState()
    val heroHeight = 300.dp
    val heroHeightPx = with(LocalDensity.current) { heroHeight.toPx() }
    val scrollProgress = remember(listState, heroHeightPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / heroHeightPx).coerceIn(0f, 1f)
        }
    }
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            MaterialTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.about),
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = ((scrollProgress.value - 0.35f) / 0.65f).coerceIn(0f, 1f),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(
                paddingValues, top = 0.dp, bottom = 0.dp, horizontalMin = 0.dp,
            ),
        ) {
            item(key = "hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heroHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.graphicsLayer {
                            alpha = (1f - scrollProgress.value * 1.4f).coerceIn(0f, 1f)
                            translationY = scrollProgress.value * heroHeightPx * 0.18f
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GhostlockCard(
                            modifier = Modifier.size(88.dp),
                            cornerRadius = 24.dp,
                            insideMargin = PaddingValues(0.dp),
                            colors = GhostlockCardDefaults.defaultColors(
                                color = colorResource(R.color.launcher_icon_background),
                            ),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.ic_launcher_foreground),
                                    contentDescription = null,
                                    modifier = Modifier.requiredSize(132.dp),
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.app_name),
                            modifier = Modifier.padding(top = 12.dp),
                            fontSize = 35.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            modifier = Modifier.padding(top = 4.dp),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "project-title") {
                GhostlockSmallTitle(text = stringResource(R.string.about_project))
            }
            item(key = "source") {
                AboutLink(
                    title = stringResource(R.string.about_source_code),
                    summary = "github.com/YuKongA/ghostlock-app",
                    onClick = { uriHandler.openUri("https://github.com/YuKongA/ghostlock-app") },
                )
            }
            item(key = "origins-title") {
                GhostlockSmallTitle(text = stringResource(R.string.about_origins))
            }
            item(key = "cybermeowfia") {
                AboutLink("CyberMeowfia", "github.com/NebuSec/CyberMeowfia") {
                    uriHandler.openUri("https://github.com/NebuSec/CyberMeowfia")
                }
            }
            item(key = "ghostlock-oneplus") {
                AboutLink("ghostlock-oneplus", "github.com/JoinChang/ghostlock-oneplus") {
                    uriHandler.openUri("https://github.com/JoinChang/ghostlock-oneplus")
                }
            }
            item(key = "popsicle") {
                AboutLink("CVE-2026-43499-popsicle", "github.com/x-spy/CVE-2026-43499-popsicle") {
                    uriHandler.openUri("https://github.com/x-spy/CVE-2026-43499-popsicle")
                }
            }

            item(key = "licenses-title") {
                GhostlockSmallTitle(text = stringResource(R.string.about_open_source))
            }
            item(key = "license") {
                AboutLink("Apache License 2.0", "github.com/YuKongA/ghostlock-app/LICENSE") {
                    uriHandler.openUri("https://github.com/YuKongA/ghostlock-app/blob/main/LICENSE")
                }
            }
            item(key = "miuix") {
                AboutLink("miuix", "github.com/compose-miuix-ui/miuix") {
                    uriHandler.openUri("https://github.com/compose-miuix-ui/miuix")
                }
            }
            item(key = "shizuku") {
                AboutLink("Shizuku", "github.com/RikkaApps/Shizuku") {
                    uriHandler.openUri("https://github.com/RikkaApps/Shizuku")
                }
            }
            item(key = "config") {
                AboutLink("Typesafe Config", "github.com/lightbend/config") {
                    uriHandler.openUri("https://github.com/lightbend/config")
                }
            }
            item(key = "compress") {
                AboutLink("Apache Commons Compress", "commons.apache.org/proper/commons-compress") {
                    uriHandler.openUri("https://commons.apache.org/proper/commons-compress/")
                }
            }
            item(key = "copyright") {
                Text(
                    text = stringResource(R.string.opensource_info),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "bottom-space") {
                Spacer(
                    Modifier
                        .height(24.dp)
                        .navigationBarsPadding()
                )
            }
        }
    }
}

@Composable
private fun AboutLink(title: String, summary: String, onClick: () -> Unit) {
    GhostlockCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
    ) {
        GhostlockArrowPreference(title = title, summary = summary, onClick = onClick)
    }
}
