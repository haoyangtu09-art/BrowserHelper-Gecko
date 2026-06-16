/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.domains.autocomplete.ShippedDomainsProvider
import mozilla.components.browser.menu2.BrowserMenuController
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.browser.toolbar.display.DisplayToolbar
import mozilla.components.concept.menu.MenuController
import mozilla.components.concept.menu.candidate.CompoundMenuCandidate
import mozilla.components.concept.menu.candidate.ContainerStyle
import mozilla.components.concept.menu.candidate.DrawableMenuIcon
import mozilla.components.concept.menu.candidate.MenuCandidate
import mozilla.components.concept.menu.candidate.RowMenuCandidate
import mozilla.components.concept.menu.candidate.SmallMenuCandidate
import mozilla.components.concept.menu.candidate.TextMenuCandidate
import mozilla.components.feature.pwa.WebAppUseCases
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.feature.toolbar.ToolbarAutocompleteFeature
import mozilla.components.feature.toolbar.ToolbarFeature
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import org.mozilla.reference.browser.BrowserUrls
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.addons.AddonsActivity
import org.mozilla.reference.browser.cookie.CookieAction
import org.mozilla.reference.browser.cookie.CookieExportHelper
import org.mozilla.reference.browser.devtools.DevToolsHelper
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.share
import org.mozilla.reference.browser.settings.SettingsActivity
import org.mozilla.reference.browser.tabs.synced.SyncedTabsActivity

@Suppress("LongParameterList")
class ToolbarIntegration(
    private val context: Context,
    private val toolbar: BrowserToolbar,
    private val toolbarParentView: View,
    historyStorage: PlacesHistoryStorage,
    store: BrowserStore,
    private val sessionUseCases: SessionUseCases,
    private val tabsUseCases: TabsUseCases,
    private val webAppUseCases: WebAppUseCases,
    sessionId: String? = null,
) : LifecycleAwareFeature,
    UserInteractionHandler {
    private val shippedDomainsProvider = ShippedDomainsProvider().also {
        it.initialize(context)
    }

    private val scope = MainScope()
    private val tabStrip: LinearLayout? = toolbarParentView.findViewById(R.id.topTabStrip)
    private val tabScroll: HorizontalScrollView? = toolbarParentView.findViewById(R.id.topTabScroll)
    private val homeButton: ImageButton? = toolbarParentView.findViewById(R.id.navHomeButton)
    private val backButton: ImageButton? = toolbarParentView.findViewById(R.id.navBackButton)
    private val forwardButton: ImageButton? = toolbarParentView.findViewById(R.id.navForwardButton)

    private fun menuToolbar(session: SessionState?): RowMenuCandidate {
        val tint = ContextCompat.getColor(context, R.color.icons)

        val forward = SmallMenuCandidate(
            contentDescription = "前进",
            icon = DrawableMenuIcon(
                context,
                mozilla.components.ui.icons.R.drawable.mozac_ic_forward_24,
                tint = tint,
            ),
            containerStyle = ContainerStyle(
                isEnabled = session?.content?.canGoForward == true,
            ),
        ) {
            sessionUseCases.goForward.invoke()
        }

        val refresh = SmallMenuCandidate(
            contentDescription = "刷新",
            icon = DrawableMenuIcon(
                context,
                mozilla.components.ui.icons.R.drawable.mozac_ic_arrow_clockwise_24,
                tint = tint,
            ),
        ) {
            sessionUseCases.reload.invoke()
        }

        val stop = SmallMenuCandidate(
            contentDescription = "停止",
            icon = DrawableMenuIcon(
                context,
                mozilla.components.ui.icons.R.drawable.mozac_ic_cross_24,
                tint = tint,
            ),
        ) {
            sessionUseCases.stopLoading.invoke()
        }

        return RowMenuCandidate(listOf(forward, refresh, stop))
    }

    private fun sessionMenuItems(sessionState: SessionState): List<MenuCandidate> =
        listOfNotNull(
            TextMenuCandidate("分享") {
                val url = sessionState.content.url
                context.share(url)
            },
            TextMenuCandidate("查看 Cookie") {
                CookieExportHelper.request(context, CookieAction.VIEW, sessionState.content.url)
            },
            TextMenuCandidate("导出 Cookie(JSON)") {
                CookieExportHelper.request(context, CookieAction.EXPORT_JSON, sessionState.content.url)
            },
            TextMenuCandidate("导出 Cookie(完整)") {
                CookieExportHelper.request(context, CookieAction.EXPORT_FULL, sessionState.content.url)
            },
            TextMenuCandidate("导出 Cookie 到下载器") {
                CookieExportHelper.request(context, CookieAction.EXPORT_TO_DOWNLOADER, sessionState.content.url)
            },
            CompoundMenuCandidate(
                text = "请求桌面版网站",
                isChecked = sessionState.content.desktopMode,
                end = CompoundMenuCandidate.ButtonType.SWITCH,
            ) { checked ->
                sessionUseCases.requestDesktopSite.invoke(checked)
            },
            if (webAppUseCases.isPinningSupported()) {
                TextMenuCandidate(
                    text = "添加到主屏幕",
                    containerStyle = ContainerStyle(
                        isVisible = webAppUseCases.isPinningSupported(),
                    ),
                ) {
                    scope.launch { webAppUseCases.addToHomescreen() }
                }
            } else {
                null
            },
            TextMenuCandidate(
                text = "在页面中查找",
            ) {
                FindInPageIntegration.launch?.invoke()
            },
        )

    private fun menuItems(sessionState: SessionState?): List<MenuCandidate> {
        val sessionMenuItems = if (sessionState != null) {
            sessionMenuItems(sessionState)
        } else {
            emptyList()
        }

        return sessionMenuItems + listOf(
            TextMenuCandidate(text = "附加组件") {
                val intent = Intent(context, AddonsActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            },
            TextMenuCandidate(text = "同步的标签页") {
                val intent = Intent(context, SyncedTabsActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            },
            TextMenuCandidate(text = "报告问题") {
                tabsUseCases.addTab(
                    url = "https://github.com/mozilla-mobile/reference-browser/issues/new",
                )
            },
            TextMenuCandidate(text = "开发者工具") {
                DevToolsHelper.toggle(context)
            },
            TextMenuCandidate(text = "设置") {
                val intent = Intent(context, SettingsActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            },
        )
    }

    private val browserMenuController: MenuController = BrowserMenuController()

    init {
        toolbar.display.apply {
            indicators = listOf(
                DisplayToolbar.Indicators.SECURITY,
                DisplayToolbar.Indicators.TRACKING_PROTECTION,
            )
            displayIndicatorSeparator = true
            menuController = browserMenuController
            hint = context.getString(R.string.toolbar_hint)

            setUrlBackground(
                ResourcesCompat.getDrawable(context.resources, R.drawable.url_background, context.theme),
            )
        }
        toolbar.addBrowserAction(
            BrowserToolbar.Button(
                imageDrawable = ContextCompat.getDrawable(
                    context,
                    mozilla.components.ui.icons.R.drawable.mozac_ic_arrow_clockwise_24,
                )!!,
                contentDescription = "刷新",
                iconTintColorResource = R.color.icons,
            ) {
                sessionUseCases.reload.invoke()
            },
        )
        homeButton?.setOnClickListener { sessionUseCases.loadUrl(BrowserUrls.DEFAULT_NEW_TAB) }
        backButton?.setOnClickListener { sessionUseCases.goBack.invoke() }
        forwardButton?.setOnClickListener { sessionUseCases.goForward.invoke() }

        toolbar.edit.apply {
            hint = context.getString(R.string.toolbar_hint)
        }

        ToolbarAutocompleteFeature(toolbar).apply {
            updateAutocompleteProviders(
                listOf(historyStorage, shippedDomainsProvider),
            )
        }

        scope.launch {
            store
                .flow()
                .map { state -> state.selectedTabId to state.tabs }
                .distinctUntilChanged()
                .collect { (selectedTabId, tabs) ->
                    val selectedTab = tabs.firstOrNull { tab -> tab.id == selectedTabId }
                    browserMenuController.submitList(menuItems(selectedTab))
                    renderTopTabs(tabs, selectedTabId)
                }
        }
    }

    private fun renderTopTabs(tabs: List<TabSessionState>, selectedTabId: String?) {
        val strip = tabStrip ?: return
        strip.removeAllViews()
        val selectedTab = tabs.firstOrNull { tab -> tab.id == selectedTabId }
        updateNavigationButton(backButton, selectedTab?.content?.canGoBack == true)
        updateNavigationButton(forwardButton, selectedTab?.content?.canGoForward == true)
        updateNavigationButton(homeButton, true)
        val tabWidth = tabWidth(tabs.size)
        tabs.forEach { tab ->
            strip.addView(tabChip(tab, selected = tab.id == selectedTabId, width = tabWidth))
        }
        strip.addView(newTabChip())
        tabScroll?.post {
            val selectedIndex = tabs.indexOfFirst { tab -> tab.id == selectedTabId }
            if (selectedIndex >= 0 && selectedIndex < strip.childCount) {
                strip.getChildAt(selectedIndex)?.let { child ->
                    tabScroll.smoothScrollTo(child.left - dp(8), 0)
                }
            }
        }
    }

    private fun updateNavigationButton(button: ImageButton?, enabled: Boolean) {
        button?.apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.38f
        }
    }

    private fun tabChip(tab: TabSessionState, selected: Boolean, width: Int): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = roundedTabBackground(if (selected) Color.rgb(55, 55, 55) else Color.rgb(30, 30, 30))
            setPadding(dp(9), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(2), dp(4), dp(2), 0)
            }
            val favicon = ImageView(context).apply {
                tab.content.icon?.let(::setImageBitmap) ?: setImageResource(R.drawable.ic_web_16)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                    marginEnd = dp(7)
                }
            }
            val title = TextView(context).apply {
                text = tabLabel(tab)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER_VERTICAL
                setTextColor(if (selected) Color.WHITE else Color.rgb(190, 190, 190))
                textSize = 13f
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            addView(favicon)
            addView(title)
            if (selected) {
                val close = TextView(context).apply {
                    text = "×"
                    textSize = 16f
                    includeFontPadding = false
                    setTextColor(Color.rgb(190, 190, 190))
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        marginStart = dp(2)
                    }
                    setOnClickListener { tabsUseCases.removeTab(tab.id) }
                }
                addView(close)
                scaleX = 0.96f
                scaleY = 0.96f
                animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            setOnClickListener { tabsUseCases.selectTab(tab.id) }
        }

    private fun newTabChip(): TextView =
        TextView(context).apply {
            text = "+"
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
            background = roundedTabBackground(Color.rgb(36, 36, 36))
            layoutParams = LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(2), dp(4), dp(6), 0)
            }
            setOnClickListener {
                tabsUseCases.addTab(BrowserUrls.DEFAULT_NEW_TAB, selectTab = true)
            }
        }

    private fun tabLabel(tab: TabSessionState): String {
        val title = tab.content.title.trim()
        if (title.isNotEmpty()) return title
        val url = tab.content.url
        return runCatching { java.net.URI(url).host?.removePrefix("www.") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: url.ifBlank { "新标签页" }
    }

    private fun tabWidth(tabCount: Int): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val available = screenWidth - dp(58)
        return when {
            tabCount <= 1 -> available.coerceIn(dp(180), dp(320))
            tabCount == 2 -> (available / 2).coerceIn(dp(150), dp(240))
            tabCount == 3 -> (available / 3).coerceIn(dp(126), dp(200))
            else -> dp(118)
        }
    }

    private fun roundedTabBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            val radius = dp(8).toFloat()
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private val toolbarFeature: ToolbarFeature = ToolbarFeature(
        toolbar,
        context.components.core.store,
        context.components.useCases.sessionUseCases.loadUrl,
        { searchTerms ->
            context.components.useCases.searchUseCases.defaultSearch.invoke(
                searchTerms = searchTerms,
                searchEngine = null,
                parentSessionId = null,
            )
        },
        sessionId,
    )

    override fun start() {
        toolbarFeature.start()
    }

    override fun stop() {
        toolbarFeature.stop()
    }

    override fun onBackPressed(): Boolean = toolbarFeature.onBackPressed()
}
